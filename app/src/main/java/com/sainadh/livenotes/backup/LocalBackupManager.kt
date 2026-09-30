package com.sainadh.livenotes.backup

import android.content.Context
import com.sainadh.livenotes.audio.WavFileWriter
import com.sainadh.livenotes.data.ApiKeyStore
import com.sainadh.livenotes.data.LibraryImportResult
import com.sainadh.livenotes.data.LibrarySnapshot
import com.sainadh.livenotes.data.NotesRepository
import com.sainadh.livenotes.data.planLibraryImport
import com.sainadh.livenotes.data.validateBackupAudioFileName
import com.sainadh.livenotes.data.validateLibrarySnapshot
import com.sainadh.livenotes.stt.ModelDownloadManager
import com.sainadh.livenotes.stt.SpeechModel
import com.sainadh.livenotes.stt.SpeechSettingsStore
import com.sainadh.livenotes.stt.verifyModelFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class BackupProgress(val message: String, val bytesProcessed: Long = 0)
data class BackupResult(
    val recordings: Int, val dailyNotes: Int, val models: Int, val files: Int,
    val skippedExisting: Int = 0, val imported: LibraryImportResult? = null,
    val includesModels: Boolean = true
)

@Serializable
internal data class BackupFile(val path: String, val bytes: Long, val sha256: String)

@Serializable
internal data class BackupManifest(
    val formatVersion: Int = 1,
    val createdAtEpochMs: Long,
    val includesModels: Boolean,
    val library: LibrarySnapshot,
    val settings: BackupSettings,
    val files: List<BackupFile>
)

/**
 * The caller holds the application maintenance gate (capture, editing and settings).
 * Streams belong to this operation and are closed even on failure. Nothing is
 * restored before the entire encrypted archive, every entry and all hashes pass.
 */
@OptIn(ExperimentalSerializationApi::class)
class LocalBackupManager(
    context: Context,
    private val repository: NotesRepository,
    private val secureSettings: ApiKeyStore,
    private val speechSettings: SpeechSettingsStore,
    private val models: ModelDownloadManager
) {
    private val filesRoot = context.filesDir
    private val stagingRoot = File(context.cacheDir, "local-backup-staging")
    private val json = Json { encodeDefaults = true }

    suspend fun exportBackup(
        output: OutputStream, password: CharArray, includeModels: Boolean = true,
        onProgress: (BackupProgress) -> Unit = {}
    ): BackupResult = withContext(Dispatchers.IO) {
        output.use { destination ->
            models.acquireArchiveLease().use {
                newStage().use { stage ->
                    onProgress(BackupProgress("Preparing notes and recordings…"))
                    val library = repository.exportSnapshot()
                    validateLibrarySnapshot(library)
                    val paths = linkedSetOf<String>()
                    for (recording in library.recordings) {
                        val name = recording.audioFileName ?: continue
                        validateAudioName(name)
                        val audio = destinationFile("recordings/$name")
                        if (!audio.isFile) throw IOException("A saved recording's audio is missing. Restore its audio before creating a complete backup.")
                        validateAudio(audio)
                        paths += "recordings/$name"
                        val timing = destinationFile("recordings/$name.words")
                        if (timing.exists()) {
                            if (!timing.isFile || timing.length() > MAX_SIDECAR_BYTES) throw IOException("A recording timing file is invalid.")
                            paths += "recordings/$name.words"
                        }
                    }
                    if (includeModels) for (model in SpeechModel.entries) {
                        val file = models.modelFile(model)
                        if (file.exists()) {
                            onProgress(BackupProgress("Checking ${model.title}…"))
                            verifyModelFile(file, model.expectedBytes, model.sha256)
                            paths += "models/${model.fileName}"
                        }
                    }
                    val entries = paths.map { path ->
                        currentCoroutineContext().ensureActive()
                        val file = destinationFile(path)
                        BackupFile(path, file.length(), hash(file))
                    }
                    val manifest = BackupManifest(createdAtEpochMs = System.currentTimeMillis(), includesModels = includeModels,
                        library = library, settings = BackupSettings(secureSettings.exportSnapshot(), speechSettings.exportSnapshot()), files = entries)
                    validateManifest(manifest)
                    val manifestFile = File(stage.directory, "manifest.json")
                    FileOutputStream(manifestFile).use { json.encodeToStream(manifest, it); it.fd.sync() }
                    if (manifestFile.length() > MAX_MANIFEST_BYTES) throw IOException("Notes exceed the supported backup metadata size.")
                    var total = 0L
                    ZipOutputStream(BackupCipher.encrypt(destination, password)).use { zip ->
                        zip.setLevel(Deflater.NO_COMPRESSION)
                        zip.putNextEntry(ZipEntry("manifest.json"))
                        manifestFile.inputStream().use { copyBounded(it, zip, manifestFile.length()) }
                        zip.closeEntry()
                        for (entry in entries) {
                            currentCoroutineContext().ensureActive()
                            onProgress(BackupProgress(if (entry.path.startsWith("models/")) "Backing up speech models…" else "Backing up recordings…", total))
                            zip.putNextEntry(ZipEntry(entry.path))
                            val actual = destinationFile(entry.path).inputStream().use { input ->
                                copyBounded(input, zip, entry.bytes) { count ->
                                    total += count; onProgress(BackupProgress("Writing encrypted backup…", total))
                                }
                            }
                            if (actual != entry.sha256) throw IOException("A file changed during backup. Please retry after recording and downloads finish.")
                            zip.closeEntry()
                        }
                    }
                    resultFor(manifest)
                }
            }
        }
    }

    /** Read back the SAF destination before reporting a successful, usable backup. */
    suspend fun validateBackup(
        input: InputStream, password: CharArray, onProgress: (BackupProgress) -> Unit = {}
    ): BackupResult = withContext(Dispatchers.IO) {
        stageBackup(input, password, onProgress).use { resultFor(it.manifest!!) }
    }

    suspend fun restoreBackup(
        input: InputStream, password: CharArray, onProgress: (BackupProgress) -> Unit = {}
    ): BackupResult = withContext(Dispatchers.IO) {
        input.use { source ->
            models.acquireArchiveLease().use {
                stageBackup(source, password, onProgress).use { stage ->
                    val manifest = stage.manifest!!
                    onProgress(BackupProgress("Checking your existing notes…"))
                    val current = repository.exportSnapshot()
                    val plan = planLibraryImport(current, manifest.library)
                    // Check every collision, including skipped identities, before changing anything.
                    for (entry in manifest.files) {
                        currentCoroutineContext().ensureActive()
                        val destination = destinationFile(entry.path)
                        if (destination.exists() && (!destination.isFile || destination.length() != entry.bytes || hash(destination) != entry.sha256)) {
                            throw IOException("An existing file conflicts with this backup. Your current notes and files have been kept.")
                        }
                    }
                    val includedAudio = manifest.library.recordings.filter { incoming ->
                        incoming.recordingId in plan.result.importedRecordingIds ||
                            current.recordings.any { it.recordingId == incoming.recordingId && it.audioFileName == incoming.audioFileName }
                    }.mapNotNull { it.audioFileName }.toSet()
                    val selectedFiles = manifest.files.filter { entry ->
                        entry.path.startsWith("models/") || entry.path.removePrefix("recordings/").removeSuffix(".words") in includedAudio
                    }
                    val previousSettings = BackupSettings(secureSettings.exportSnapshot(), speechSettings.exportSnapshot())
                    val created = mutableListOf<File>()
                    var committed = false
                    var imported: LibraryImportResult? = null
                    try {
                        // No cancellation gap between the durable database commit and ownership of its files.
                        withContext(NonCancellable) {
                            for (entry in selectedFiles) {
                                val destination = destinationFile(entry.path)
                                if (!destination.exists()) {
                                    if (destination.parentFile?.let { it.isDirectory || it.mkdirs() } != true) throw IOException("Could not create restored file storage.")
                                    // Same private filesystem: complete verified file appears atomically; no overwrite.
                                    Files.move(File(stage.directory, entry.path).toPath(), destination.toPath())
                                    created += destination
                                }
                            }
                            secureSettings.restoreSnapshot(manifest.settings.secure)
                            speechSettings.restoreSnapshot(manifest.settings.speech)
                            imported = repository.importSnapshot(manifest.library)
                            committed = true
                        }
                    } catch (error: Throwable) {
                        if (!committed) withContext(NonCancellable) {
                            runCatching { secureSettings.restoreSnapshot(previousSettings.secure) }.exceptionOrNull()?.let(error::addSuppressed)
                            runCatching { speechSettings.restoreSnapshot(previousSettings.speech) }.exceptionOrNull()?.let(error::addSuppressed)
                            created.asReversed().forEach { file ->
                                if (file.exists() && !file.delete()) error.addSuppressed(IOException("Could not clean up a newly restored file."))
                            }
                        }
                        throw error
                    }
                    val added = imported!!
                    BackupResult(added.recordingsAdded, added.dailyNotesAdded, selectedFiles.count { it.path.startsWith("models/") },
                        created.size, added.recordingsSkipped + added.dailyNotesSkipped, added, manifest.includesModels)
                }
            }
        }
    }

    private suspend fun stageBackup(input: InputStream, password: CharArray, onProgress: (BackupProgress) -> Unit): StagedBackup {
        val stage = newStage()
        try {
            input.use { source ->
                BackupCipher.decrypt(source, password).use { clear ->
                    // ZIP does not consume its central directory. Do not let ZIP close the AEAD stream;
                    // drain that stream after all entries to authenticate the mandatory final frame.
                    val nonClosing = object : FilterInputStream(clear) { override fun close() = Unit }
                    ZipInputStream(nonClosing).use { zip ->
                        onProgress(BackupProgress("Checking backup password and contents…"))
                        val first = zip.nextEntry ?: throw IOException("The backup is empty.")
                        if (first.name != "manifest.json" || first.isDirectory) throw IOException("Backup metadata is missing.")
                        val metadata = File(stage.directory, "manifest.json")
                        FileOutputStream(metadata).use { output -> copyLimited(zip, output, MAX_MANIFEST_BYTES); output.fd.sync() }
                        zip.closeEntry()
                        val manifest = try {
                            metadata.inputStream().use { json.decodeFromStream<BackupManifest>(it) }
                        } catch (_: SerializationException) {
                            // Parser exceptions can include snippets containing private notes or API keys.
                            throw IOException("The backup metadata is damaged or uses an unsupported format.")
                        }
                        validateManifest(manifest)
                        stage.manifest = manifest
                        val expected = manifest.files.associateBy { it.path }
                        val seen = hashSetOf<String>()
                        var total = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val entry = zip.nextEntry ?: break
                            val descriptor = expected[entry.name]
                                ?: throw IOException("The backup contains an unexpected file.")
                            if (entry.isDirectory || !seen.add(entry.name)) throw IOException("The backup contains a duplicate or invalid entry.")
                            val file = File(stage.directory, descriptor.path)
                            if (file.parentFile?.let { it.isDirectory || it.mkdirs() } != true) throw IOException("Not enough private storage to check the backup.")
                            val actual = FileOutputStream(file).use { output ->
                                val digest = copyBounded(zip, output, descriptor.bytes) { bytes ->
                                    total += bytes; onProgress(BackupProgress("Checking backup files…", total))
                                }
                                output.fd.sync(); digest
                            }
                            if (actual != descriptor.sha256) throw IOException("A backup file is damaged.")
                            zip.closeEntry()
                            if (descriptor.path.startsWith("models/")) {
                                val model = SpeechModel.entries.single { "models/${it.fileName}" == descriptor.path }
                                verifyModelFile(file, model.expectedBytes, model.sha256)
                            } else if (descriptor.path.endsWith(".wav")) validateAudio(file)
                        }
                        if (seen != expected.keys) throw IOException("The backup is missing files.")
                    }
                    val discard = ByteArray(64 * 1024)
                    var trailing = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = clear.read(discard)
                        if (count < 0) break
                        trailing += count
                        if (trailing > MAX_CENTRAL_DIRECTORY_BYTES) throw IOException("The backup ZIP directory exceeds its size limit.")
                    }
                }
            }
            return stage
        } catch (error: Throwable) {
            stage.close()
            throw error
        }
    }

    private fun newStage(): StagedBackup = synchronized(stagesGuard) {
        if (!stagingRoot.isDirectory && !stagingRoot.mkdirs()) throw IOException("Could not create private backup storage.")
        // A killed process may leave verified plaintext in its private cache. Remove
        // abandoned work before starting again, without disturbing an active checker.
        stagingRoot.listFiles()?.filter { it.absolutePath !in activeStages }?.forEach {
            if (!it.deleteRecursively()) throw IOException("Could not clear an interrupted backup's temporary files.")
        }
        val directory = File(stagingRoot, UUID.randomUUID().toString())
        if (!directory.mkdir()) throw IOException("Could not create private backup storage.")
        activeStages += directory.absolutePath
        StagedBackup(directory)
    }

    private class StagedBackup(val directory: File, var manifest: BackupManifest? = null) : Closeable {
        override fun close() = synchronized(stagesGuard) {
            directory.deleteRecursively()
            activeStages.remove(directory.absolutePath)
            Unit
        }
    }

    private fun destinationFile(path: String): File {
        validateArchivePath(path)
        val root = if (path.startsWith("models/")) models.modelFile(SpeechModel.entries.single { "models/${it.fileName}" == path }).parentFile!!
            else File(filesRoot, "recordings")
        val file = File(root, path.substringAfter('/'))
        if (file.canonicalFile.parentFile != root.canonicalFile) throw IOException("Invalid backup file location.")
        return file
    }

    private fun resultFor(manifest: BackupManifest) = BackupResult(
        (manifest.library.recordings.map { it.recordingId } + manifest.library.segments.map { it.recordingId } +
            manifest.library.chunks.map { "legacy:${it.dateKey}" }).toSet().size,
        manifest.library.dailyNotes.size, manifest.files.count { it.path.startsWith("models/") }, manifest.files.size,
        includesModels = manifest.includesModels
    )

    companion object {
        private val stagesGuard = Any()
        private val activeStages = hashSetOf<String>()
        internal const val MAX_MANIFEST_BYTES = 32L * 1024 * 1024
        private const val MAX_SIDECAR_BYTES = 16L * 1024 * 1024
        private const val MAX_FILE_BYTES = 4L * 1024 * 1024 * 1024 + 44
        private const val MAX_TOTAL_BYTES = 128L * 1024 * 1024 * 1024
        private const val MAX_FILES = 100_000
        private const val MAX_CENTRAL_DIRECTORY_BYTES = 64L * 1024 * 1024

        internal fun validateArchivePath(path: String) {
            if (path.startsWith("models/") && SpeechModel.entries.any { "models/${it.fileName}" == path }) return
            if (!path.startsWith("recordings/")) throw IOException("Unsupported backup file location.")
            val name = path.removePrefix("recordings/")
            validateAudioName(name.removeSuffix(".words"))
        }

        private fun validateAudioName(name: String) {
            validateBackupAudioFileName(name)
            require(name.endsWith(".wav") && name.length > 4) { "Unsupported recording filename in backup." }
        }

        internal fun validateManifest(manifest: BackupManifest) {
            require(manifest.formatVersion == 1 && manifest.createdAtEpochMs >= 0) { "Unsupported backup metadata." }
            validateLibrarySnapshot(manifest.library)
            manifest.settings.validate()
            require(manifest.files.size <= MAX_FILES && manifest.files.map { it.path }.toSet().size == manifest.files.size) { "Too many or duplicate backup files." }
            val referenced = manifest.library.recordings.mapNotNull { it.audioFileName }.toSet()
            val available = manifest.files.map { it.path }.toSet()
            var total = 0L
            for (entry in manifest.files) {
                validateArchivePath(entry.path)
                require(entry.bytes in 0..MAX_FILE_BYTES && entry.sha256.matches(Regex("[0-9a-f]{64}"))) { "Invalid backup file metadata." }
                total += entry.bytes
                require(total <= MAX_TOTAL_BYTES) { "Backup exceeds the supported total size." }
                if (entry.path.startsWith("models/")) {
                    val model = SpeechModel.entries.single { "models/${it.fileName}" == entry.path }
                    require(manifest.includesModels && entry.bytes == model.expectedBytes && entry.sha256 == model.sha256) { "Backup speech model does not match the trusted model catalog." }
                } else {
                    val name = entry.path.removePrefix("recordings/").removeSuffix(".words")
                    require(name in referenced) { "A backup file has no matching recording." }
                    if (entry.path.endsWith(".words")) require(entry.bytes <= MAX_SIDECAR_BYTES) { "Recording timing file is too large." }
                }
            }
            require(referenced.all { "recordings/$it" in available }) { "The backup is missing a recording's audio." }
        }

        private fun validateAudio(file: File) {
            // recover() opens completed files read-only; the guard excludes its
            // separate, mutating .part recovery path during both export and import.
            if (!file.isFile) throw IOException("A saved recording's audio is missing.")
            if (WavFileWriter.recover(file) == null) throw IOException("A saved recording contains no valid audio.")
        }

        internal fun hash(file: File): String = file.inputStream().use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
            digest.digest().joinToString("") { "%02x".format(it) }
        }

        private suspend fun copyBounded(input: InputStream, output: OutputStream, expected: Long, progress: (Long) -> Unit = {}): String {
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            var report = 0L
            val buffer = ByteArray(64 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                total += count
                if (total > expected) throw IOException("A backup file exceeds its declared size.")
                output.write(buffer, 0, count); digest.update(buffer, 0, count); report += count
                if (report >= 1024 * 1024) { progress(report); report = 0 }
            }
            if (report > 0) progress(report)
            if (total != expected) throw IOException("A backup file is incomplete.")
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        private suspend fun copyLimited(input: InputStream, output: OutputStream, limit: Long) {
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) return
                total += count
                if (total > limit) throw IOException("Backup metadata exceeds its size limit.")
                output.write(buffer, 0, count)
            }
        }
    }
}
