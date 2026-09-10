package com.sainadh.livenotes.data

import com.sainadh.livenotes.stt.TranscriptStatus.*
import com.sainadh.livenotes.stt.TranscriptUpdate
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class SegmentTimingPersistenceTest {
    private class MemoryDao : TranscriptSegmentDao {
        val rows = mutableMapOf<Pair<String, Long>, TranscriptSegmentEntity>()
        override fun observeAll() = flowOf(rows.values.toList())
        override suspend fun get(recordingId: String, segmentId: Long) = rows[recordingId to segmentId]
        override suspend fun insert(segment: TranscriptSegmentEntity) { rows[segment.recordingId to segment.segmentId] = segment }
        override suspend fun update(segment: TranscriptSegmentEntity) { rows[segment.recordingId to segment.segmentId] = segment }
        override suspend fun pendingSummary(dateKey: String) = rows.values.filter { it.dateKey == dateKey && it.revision > it.summarizedRevision }
        override suspend fun markSummarized(recordingId: String, segmentId: Long, revision: Long) = Unit
    }

    @Test fun partialRevisionsKeepOriginalStartAndLatestEnd() = runTest {
        val dao = MemoryDao()
        dao.save("r", TranscriptUpdate(0, "hello", PARTIAL, startMs = 100, endMs = 500), "day1", 1)
        dao.save("r", TranscriptUpdate(0, "hello world", PARTIAL, startMs = 200, endMs = 1_000), "day2", 2)
        dao.save("r", TranscriptUpdate(0, "hello world", FINAL, startMs = null, endMs = null), "day2", 3)
        val row = dao.get("r", 0)!!
        assertEquals(100L, row.startMs)
        assertEquals(1_000L, row.endMs)
        assertEquals("day1", row.dateKey)
        assertEquals(3L, row.revision)
        assertEquals(1, dao.rows.size)
    }

    @Test fun timingOnlyUpdatesDoNotRequestAnotherSummary() = runTest {
        val dao = MemoryDao()
        dao.save("r", TranscriptUpdate(0, "same", PARTIAL, startMs = 0, endMs = 500), "today", 1)
        dao.save("r", TranscriptUpdate(0, "same", PARTIAL, startMs = 0, endMs = 900), "today", 2)
        val row = dao.get("r", 0)!!
        assertEquals(900L, row.endMs)
        assertEquals(1L, row.revision)
        assertEquals(2L, row.updatedAtEpochMs)
    }

    @Test fun finalizedAndInterruptedBoundsRejectStalePartials() = runTest {
        listOf(FINAL, INTERRUPTED).forEach { status ->
            val dao = MemoryDao()
            dao.save("r", TranscriptUpdate(0, "saved", status, startMs = 100, endMs = 1_000), "today", 1)
            dao.save("r", TranscriptUpdate(0, "late", PARTIAL, startMs = 0, endMs = 2_000), "today", 2)
            assertEquals("saved", dao.get("r", 0)!!.text)
            assertEquals(100L, dao.get("r", 0)!!.startMs)
            assertEquals(1_000L, dao.get("r", 0)!!.endMs)
        }
    }
}
