package com.sainadh.livenotes.desktop.data

import com.sainadh.livenotes.audio.WavFileWriter
import com.sainadh.livenotes.data.RecordingSegment
import com.sainadh.livenotes.data.alignedWordCues
import com.sainadh.livenotes.data.recordingWordCues
import com.sainadh.livenotes.data.transcriptText
import com.sainadh.livenotes.desktop.RecordingDocument
import com.sainadh.livenotes.desktop.RecordingEntry
import com.sainadh.livenotes.desktop.SpeakerName
import com.sainadh.livenotes.desktop.TranscriptTurn
import com.sainadh.livenotes.stt.NativeWordTimingFile
import com.sainadh.livenotes.stt.TranscriptStatus
import com.sainadh.livenotes.stt.TranscriptUpdate
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet

/** Serialized small writes during capture; full transcript queries only on demand. */
class RecordingStore(private val paths: AppPaths) : AutoCloseable {
    private val db: Connection
    private val json = Json { ignoreUnknownKeys = true }
    init {
        Class.forName("org.sqlite.JDBC")
        db = DriverManager.getConnection("jdbc:sqlite:${java.io.File(paths.root, "notes.db").absolutePath}")
        db.createStatement().use { s ->
            s.execute("PRAGMA foreign_keys=ON")
            s.execute("PRAGMA journal_mode=WAL")
            s.execute("PRAGMA synchronous=FULL")
            s.execute("PRAGMA busy_timeout=5000")
            val version = s.executeQuery("PRAGMA user_version").use { it.next(); it.getInt(1) }
            check(version <= 1) { "This library was created by a newer version of LiveMeetingNotes." }
            s.execute("""CREATE TABLE IF NOT EXISTS recordings (
                id TEXT PRIMARY KEY, title TEXT NOT NULL, started INTEGER NOT NULL,
                duration INTEGER NOT NULL DEFAULT 0, audio INTEGER NOT NULL DEFAULT 0,
                status TEXT NOT NULL DEFAULT 'recording', preview TEXT NOT NULL DEFAULT '',
                summary TEXT NOT NULL DEFAULT '', context TEXT NOT NULL DEFAULT '',
                actions TEXT NOT NULL DEFAULT '[]', speaker_status TEXT NOT NULL DEFAULT 'Not analyzed')""")
            s.execute("""CREATE TABLE IF NOT EXISTS segments (
                recording_id TEXT NOT NULL REFERENCES recordings(id) ON DELETE CASCADE,
                segment_id INTEGER NOT NULL, text TEXT NOT NULL, status TEXT NOT NULL,
                append_previous INTEGER NOT NULL, start_ms INTEGER, end_ms INTEGER,
                PRIMARY KEY(recording_id,segment_id))""")
            s.execute("""CREATE TABLE IF NOT EXISTS speakers (
                recording_id TEXT NOT NULL REFERENCES recordings(id) ON DELETE CASCADE,
                speaker_id TEXT NOT NULL, name TEXT NOT NULL, PRIMARY KEY(recording_id,speaker_id))""")
            s.execute("""CREATE TABLE IF NOT EXISTS turns (
                recording_id TEXT NOT NULL REFERENCES recordings(id) ON DELETE CASCADE,
                turn_id INTEGER NOT NULL, speaker_id TEXT, start_ms INTEGER, end_ms INTEGER,
                start_char INTEGER NOT NULL, end_char INTEGER NOT NULL, overlapping INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(recording_id,turn_id))""")
            s.execute("PRAGMA user_version=1")
        }
    }
    private fun update(sql: String, vararg args: Any?): Int = db.prepareStatement(sql).use { p ->
        args.forEachIndexed { index, value -> p.setObject(index + 1, value) }; p.executeUpdate()
    }
    private fun <T> query(sql: String, vararg args: Any?, read: (ResultSet) -> T): List<T> = db.prepareStatement(sql).use { p ->
        args.forEachIndexed { index, value -> p.setObject(index + 1, value) }
        p.executeQuery().use { r -> buildList { while (r.next()) add(read(r)) } }
    }
    private fun <T> transaction(block: () -> T): T {
        db.autoCommit = false
        try { val value = block(); db.commit(); return value }
        catch (error: Throwable) { db.rollback(); throw error }
        finally { db.autoCommit = true }
    }
    @Synchronized fun begin(id: String, startedAtMs: Long) {
        paths.audio(id) // Validate before persistence.
        update("INSERT INTO recordings(id,title,started) VALUES(?,?,?)", id, "Meeting", startedAtMs)
    }
    @Synchronized fun saveSegment(id: String, update: TranscriptUpdate) {
        this.update("""INSERT INTO segments(recording_id,segment_id,text,status,append_previous,start_ms,end_ms)
            VALUES(?,?,?,?,?,?,?) ON CONFLICT(recording_id,segment_id) DO UPDATE SET
            text=excluded.text,status=excluded.status,append_previous=excluded.append_previous,
            start_ms=excluded.start_ms,end_ms=excluded.end_ms WHERE segments.status='PARTIAL'""",
            id, update.segmentId, update.text, update.status.name, if (update.appendToPrevious) 1 else 0, update.startMs, update.endMs)
    }
    @Synchronized fun finish(id: String, hasAudio: Boolean, durationMs: Long, preview: String, interrupted: Boolean) {
        transaction {
            update("UPDATE segments SET status='INTERRUPTED' WHERE recording_id=? AND status='PARTIAL'", id)
            update("UPDATE recordings SET audio=?,duration=?,preview=?,status=? WHERE id=?",
                if (hasAudio) 1 else 0, durationMs, preview.take(200), if (interrupted) "interrupted" else "ready", id)
        }
    }
    private fun entry(r: ResultSet) = RecordingEntry(
        r.getString("id"), r.getString("title"), r.getLong("started"), r.getLong("duration"),
        r.getInt("audio") == 1, r.getString("preview"), r.getInt("speaker_count"), r.getString("status") == "interrupted")
    private val entrySql = "SELECT r.*, (SELECT COUNT(*) FROM speakers s WHERE s.recording_id=r.id) AS speaker_count FROM recordings r"
    @Synchronized fun list(): List<RecordingEntry> = query("$entrySql WHERE status<>'recording' ORDER BY started DESC", read = ::entry)
    @Synchronized fun segments(id: String): List<RecordingSegment> = query(
        "SELECT * FROM segments WHERE recording_id=? ORDER BY segment_id", id) { r ->
        RecordingSegment(r.getLong("segment_id"), r.getString("text"), TranscriptStatus.valueOf(r.getString("status")),
            r.getInt("append_previous") == 1, r.nullableLong("start_ms"), r.nullableLong("end_ms"))
    }
    @Synchronized fun document(id: String): RecordingDocument? {
        val info = query("$entrySql WHERE id=?", id, read = ::entry).firstOrNull() ?: return null
        val segments = segments(id)
        val text = transcriptText(segments)
        val words = alignedWordCues(text, NativeWordTimingFile.read(paths.audio(id)), info.durationMs)
            .ifEmpty { recordingWordCues(segments) }
        val names = query("SELECT speaker_id,name FROM speakers WHERE recording_id=? ORDER BY rowid", id) {
            SpeakerName(it.getString(1), it.getString(2))
        }
        val turns = query("SELECT * FROM turns WHERE recording_id=? ORDER BY turn_id", id) { r ->
            TranscriptTurn(r.getInt("turn_id"), r.getString("speaker_id"), r.nullableLong("start_ms"), r.nullableLong("end_ms"),
                r.getInt("start_char"), r.getInt("end_char"), r.getInt("overlapping") == 1)
        }
        return query("SELECT summary,actions,speaker_status FROM recordings WHERE id=?", id) {
            RecordingDocument(info, text, words, turns, names, it.getString(1), json.decodeFromString(it.getString(2)), it.getString(3))
        }.single()
    }
    @Synchronized fun rename(id: String, title: String) {
        require(title.trim().isNotEmpty()); update("UPDATE recordings SET title=? WHERE id=?", title.trim().take(120), id)
    }
    @Synchronized fun summaryContext(id: String): String = query("SELECT context FROM recordings WHERE id=?", id) { it.getString(1) }.firstOrNull().orEmpty()
    @Synchronized fun summaryState(id: String): Triple<String, String, List<String>> = query(
        "SELECT summary,context,actions FROM recordings WHERE id=?", id) {
        Triple(it.getString(1), it.getString(2), json.decodeFromString<List<String>>(it.getString(3)))
    }.firstOrNull() ?: Triple("", "", emptyList())
    @Synchronized fun saveSummary(id: String, summary: String, context: String, actions: List<String>) {
        update("UPDATE recordings SET summary=?,context=?,actions=? WHERE id=?", summary, context, json.encodeToString(actions), id)
    }
    @Synchronized fun saveSpeakers(id: String, names: List<SpeakerName>, turns: List<TranscriptTurn>) {
        require(names.map { it.id }.distinct().size == names.size)
        val textLength = transcriptText(segments(id)).length
        require(turns.all { it.startChar in 0..it.endChar && it.endChar <= textLength && (it.speakerId == null || names.any { name -> name.id == it.speakerId }) })
        transaction {
            update("DELETE FROM turns WHERE recording_id=?", id)
            update("DELETE FROM speakers WHERE recording_id=?", id)
            names.forEach { update("INSERT INTO speakers(recording_id,speaker_id,name) VALUES(?,?,?)", id, it.id, it.name) }
            turns.forEach { update("INSERT INTO turns(recording_id,turn_id,speaker_id,start_ms,end_ms,start_char,end_char,overlapping) VALUES(?,?,?,?,?,?,?,?)",
                id, it.id, it.speakerId, it.startMs, it.endMs, it.startChar, it.endChar, if (it.overlapping) 1 else 0) }
            update("UPDATE recordings SET speaker_status=? WHERE id=?", if (names.isEmpty()) "No speakers detected" else "Speakers ready", id)
        }
    }
    @Synchronized fun renameSpeaker(id: String, speakerId: String, name: String) {
        require(name.trim().isNotEmpty()); update("UPDATE speakers SET name=? WHERE recording_id=? AND speaker_id=?", name.trim().take(80), id, speakerId)
    }
    @Synchronized fun mergeSpeakers(id: String, source: String, target: String) {
        require(source != target)
        require(query("SELECT speaker_id FROM speakers WHERE recording_id=? AND speaker_id IN (?,?)", id, source, target) { it.getString(1) }.size == 2)
        transaction {
            update("UPDATE turns SET speaker_id=? WHERE recording_id=? AND speaker_id=?", target, id, source)
            update("DELETE FROM speakers WHERE recording_id=? AND speaker_id=?", id, source)
        }
    }
    @Synchronized fun assignSpeaker(id: String, turnId: Int, speakerId: String?) {
        require(speakerId == null || query("SELECT 1 FROM speakers WHERE recording_id=? AND speaker_id=?", id, speakerId) { true }.isNotEmpty())
        update("UPDATE turns SET speaker_id=?,overlapping=0 WHERE recording_id=? AND turn_id=?", speakerId, id, turnId)
    }
    @Synchronized fun delete(id: String) {
        check(query("SELECT status FROM recordings WHERE id=?", id) { it.getString(1) }.firstOrNull() != "recording")
        val trash = java.io.File(paths.work, "trash/$id").apply { mkdirs() }
        val moved = mutableListOf<Pair<java.io.File, java.io.File>>()
        try {
            listOf(paths.audio(id), java.io.File(paths.recordings, "$id.wav.words")).filter { it.exists() }.forEach { original ->
                val backup = java.io.File(trash, original.name)
                java.nio.file.Files.move(original.toPath(), backup.toPath())
                moved += original to backup
            }
            transaction { update("DELETE FROM recordings WHERE id=?", id) }
        } catch (error: Throwable) {
            moved.asReversed().forEach { (original, backup) ->
                runCatching { java.nio.file.Files.move(backup.toPath(), original.toPath()) }.exceptionOrNull()?.let(error::addSuppressed)
            }
            throw error
        }
        // A crash before commit restores these files on startup; after commit
        // it only leaves removable trash, never a live row pointing at missing audio.
        trash.deleteRecursively()
    }
    @Synchronized fun recoverInterrupted(): Int {
        recoverDeletes()
        val ids = query("SELECT id FROM recordings WHERE status='recording'") { it.getString(1) }
        ids.forEach { id ->
            val audio = runCatching { WavFileWriter.recover(paths.audio(id)) }.getOrNull()
            finish(id, audio != null, audio?.durationMs ?: 0L, transcriptText(segments(id)).take(200), interrupted = true)
        }
        return ids.size
    }
    private fun recoverDeletes() {
        java.io.File(paths.work, "trash").listFiles()?.filter { it.isDirectory }?.forEach { trash ->
            val id = trash.name
            val audio = runCatching { paths.audio(id) }.getOrNull() ?: return@forEach
            if (query("SELECT 1 FROM recordings WHERE id=?", id) { true }.isNotEmpty()) {
                listOf(audio, java.io.File(paths.recordings, "$id.wav.words")).forEach { destination ->
                    val backup = java.io.File(trash, destination.name)
                    if (backup.isFile && !destination.exists()) java.nio.file.Files.move(backup.toPath(), destination.toPath())
                }
            }
            trash.deleteRecursively()
        }
    }
    @Synchronized override fun close() { db.close() }
}

private fun ResultSet.nullableLong(column: String): Long? { val value = getLong(column); return if (wasNull()) null else value }
