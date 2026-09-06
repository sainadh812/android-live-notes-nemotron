package com.sainadh.livenotes

import com.sainadh.livenotes.ai.formatTranscriptSegments
import com.sainadh.livenotes.data.TranscriptSegmentDao
import com.sainadh.livenotes.data.TranscriptSegmentEntity
import com.sainadh.livenotes.stt.TranscriptStatus.*
import com.sainadh.livenotes.stt.TranscriptUpdate
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class TranscriptStorageTest {
    private class MemoryDao : TranscriptSegmentDao {
        val rows = mutableMapOf<Pair<String, Long>, TranscriptSegmentEntity>()
        override suspend fun get(recordingId: String, segmentId: Long) = rows[recordingId to segmentId]
        override suspend fun insert(segment: TranscriptSegmentEntity) {
            check(rows.put(segment.recordingId to segment.segmentId, segment) == null)
        }
        override suspend fun update(segment: TranscriptSegmentEntity) {
            rows[segment.recordingId to segment.segmentId] = segment
        }
        override suspend fun pendingSummary(dateKey: String) = rows.values.filter { it.dateKey == dateKey && it.revision > it.summarizedRevision }
        override suspend fun markSummarized(recordingId: String, segmentId: Long, revision: Long) = Unit
    }

    @Test fun partialRevisionsReplaceOneRowInsteadOfRetainingSnapshots() = runTest {
        val dao = MemoryDao()
        repeat(1_000) { index ->
            dao.save("recording", TranscriptUpdate(0, "word ".repeat(index + 1), PARTIAL), "today", index.toLong())
        }
        assertEquals(1, dao.rows.size)
        val row = dao.rows.values.single()
        assertEquals("word ".repeat(1_000), row.text)
        assertEquals(1_000L, row.revision)
        dao.save("recording", TranscriptUpdate(0, "complete words", FINAL), "today", 1001)
        assertEquals(1, dao.rows.size)
        assertEquals("FINAL", dao.rows.values.single().status)
    }

    @Test fun finalSegmentRejectsLatePartialAndDuplicateFinal() = runTest {
        val dao = MemoryDao()
        dao.save("a", TranscriptUpdate(0, "correct", FINAL), "today", 1)
        dao.save("a", TranscriptUpdate(0, "stale", PARTIAL), "today", 2)
        dao.save("a", TranscriptUpdate(0, "correct", FINAL), "today", 3)
        assertEquals("correct", dao.get("a", 0)!!.text)
        assertEquals(1L, dao.get("a", 0)!!.revision)
    }

    @Test fun interruptedUtteranceSurvivesNextUtteranceAndRecording() = runTest {
        val dao = MemoryDao()
        dao.save("a", TranscriptUpdate(0, "send proposal Friday", PARTIAL), "today", 1)
        dao.save("a", TranscriptUpdate(0, "send proposal Friday", INTERRUPTED), "today", 2)
        dao.save("a", TranscriptUpdate(1, "also book a room", FINAL), "today", 3)
        dao.save("b", TranscriptUpdate(0, "another recording", FINAL), "today", 4)
        val pending = dao.pendingSummary("today")
        assertEquals(3, pending.size)
        assertEquals("INTERRUPTED", dao.get("a", 0)!!.status)
        assertEquals("send proposal Friday", dao.get("a", 0)!!.text)
    }

    @Test fun revisionAcrossMidnightKeepsOriginalDate() = runTest {
        val dao = MemoryDao()
        dao.save("a", TranscriptUpdate(0, "before", PARTIAL), "yesterday", 1)
        val changedDate = dao.save("a", TranscriptUpdate(0, "before midnight", FINAL), "today", 2)
        assertEquals("yesterday", changedDate)
        assertEquals(1L, dao.get("a", 0)!!.createdAtEpochMs)
        assertEquals(2L, dao.get("a", 0)!!.updatedAtEpochMs)
    }

    @Test fun nativeWhitespaceAndEmptyCorrectionsArePreserved() = runTest {
        val dao = MemoryDao()
        dao.save("a", TranscriptUpdate(0, " ", FINAL, false, true), "today", 1)
        dao.save("a", TranscriptUpdate(1, "wrong guess", PARTIAL, false, true), "today", 2)
        dao.save("a", TranscriptUpdate(1, "", PARTIAL, false, true), "today", 3)
        dao.save("a", TranscriptUpdate(2, "", FINAL, true, true), "today", 4)
        assertEquals(2, dao.rows.size)
        assertEquals(" ", dao.get("a", 0)!!.text)
        assertEquals("", dao.get("a", 1)!!.text)
        assertEquals(2L, dao.get("a", 1)!!.revision)
    }

    @Test fun identicalHypothesisDoesNotCreateAnotherRevision() = runTest {
        val dao = MemoryDao()
        repeat(10) { dao.save("a", TranscriptUpdate(0, "same", PARTIAL), "today", it.toLong()) }
        assertEquals(1L, dao.get("a", 0)!!.revision)
    }

    @Test fun summaryInputRetainsRecoveryIdentityAndWithdrawnText() = runTest {
        val dao = MemoryDao()
        dao.save("a", TranscriptUpdate(0, "uncertain promise", INTERRUPTED), "today", 1)
        dao.save("a", TranscriptUpdate(1, "retracted", PARTIAL), "today", 2)
        dao.save("a", TranscriptUpdate(1, "", PARTIAL), "today", 3)
        val records = Json.parseToJsonElement(formatTranscriptSegments(dao.pendingSummary("today"))).jsonArray
        assertEquals("INTERRUPTED", records[0].jsonObject["status"]!!.jsonPrimitive.content)
        assertEquals("0", records[0].jsonObject["segment"]!!.jsonPrimitive.content)
        assertEquals("", records[1].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("2", records[1].jsonObject["revision"]!!.jsonPrimitive.content)
    }

    @Test fun phoneClockChangesDoNotReorderNativePieces() = runTest {
        val dao = MemoryDao()
        dao.save("a", TranscriptUpdate(0, "first ", FINAL, false, true), "today", 100)
        dao.save("a", TranscriptUpdate(1, "second", FINAL, true, true), "today", 90)
        val input = dao.rows.values.sortedBy { it.createdAtEpochMs }
        val records = Json.parseToJsonElement(formatTranscriptSegments(input)).jsonArray
        assertEquals("first second", records.joinToString("") { it.jsonObject["text"]!!.jsonPrimitive.content })
    }
}
