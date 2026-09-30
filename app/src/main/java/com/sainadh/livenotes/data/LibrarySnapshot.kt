package com.sainadh.livenotes.data

import com.sainadh.livenotes.stt.TranscriptStatus
import java.time.LocalDate
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** Contains library data only; API keys, settings, and audio bytes are deliberately separate. */
@Serializable
data class LibrarySnapshot(
    val schemaVersion: Int = 1,
    val dailyNotes: List<DailyNoteEntity> = emptyList(),
    val chunks: List<TranscriptChunkEntity> = emptyList(),
    val segments: List<TranscriptSegmentEntity> = emptyList(),
    val recordings: List<RecordingEntity> = emptyList(),
    val categories: List<NoteCategoryEntity> = emptyList(),
    val organizations: List<NoteOrganizationEntity> = emptyList()
)

data class LibraryImportResult(
    val recordingsAdded: Int,
    val dailyNotesAdded: Int,
    val legacyChunksAdded: Int,
    val segmentsAdded: Int,
    val categoriesAdded: Int,
    val organizationsAdded: Int,
    val recordingsSkipped: Int,
    val dailyNotesSkipped: Int,
    val legacyChunksSkipped: Int,
    val segmentsSkipped: Int,
    val categoriesReused: Int,
    val organizationsSkipped: Int,
    val importedRecordingIds: Set<String>
)

data class LibraryImportPlan(val additions: LibrarySnapshot, val result: LibraryImportResult)

fun validateLibrarySnapshot(snapshot: LibrarySnapshot) {
    require(snapshot.schemaVersion == 1) { "This backup version is not supported" }
    requireUnique(snapshot.dailyNotes.map { it.dateKey }, "daily note dates")
    requireUnique(snapshot.chunks.map { it.id }, "legacy transcript IDs")
    requireUnique(snapshot.segments.map { it.recordingId to it.segmentId }, "transcript segment IDs")
    requireUnique(snapshot.recordings.map { it.recordingId }, "recording IDs")
    requireUnique(snapshot.categories.map { it.id }, "category IDs")
    requireUnique(snapshot.categories.map { it.normalizedName }, "category names")
    requireUnique(snapshot.organizations.map { it.noteKey }, "note IDs")

    snapshot.dailyNotes.forEach {
        validateBackupDate(it.dateKey)
        require(it.updatedAtEpochMs >= 0) { "Invalid daily note timestamp" }
        Json.decodeFromString(ListSerializer(String.serializer()), it.actionItemsJson)
    }
    snapshot.chunks.forEach {
        require(it.id > 0) { "Invalid legacy transcript ID" }
        validateBackupDate(it.dateKey)
        require(it.createdAtEpochMs >= 0) { "Invalid transcript timestamp" }
    }
    snapshot.recordings.forEach {
        validateRecordingId(it.recordingId)
        validateBackupDate(it.dateKey)
        require(it.durationMs >= 0 && it.startedAtEpochMs >= 0 && it.updatedAtEpochMs >= 0) { "Invalid recording time" }
        require(it.audioStatus == RecordingAudioStatus.READY || it.audioStatus == RecordingAudioStatus.UNAVAILABLE) {
            "Finish recording before backing up or restoring your library"
        }
        require(it.audioStatus != RecordingAudioStatus.READY || it.audioFileName != null) { "A ready recording is missing its audio filename" }
        it.audioFileName?.let(::validateBackupAudioFileName)
    }
    snapshot.segments.forEach {
        validateRecordingId(it.recordingId)
        validateBackupDate(it.dateKey)
        require(it.segmentId >= 0 && it.revision > 0 && it.summarizedRevision in 0..it.revision) { "Invalid transcript revision" }
        require(it.createdAtEpochMs >= 0 && it.updatedAtEpochMs >= 0) { "Invalid transcript timestamp" }
        require(TranscriptStatus.entries.any { status -> status.name == it.status }) { "Invalid transcript status" }
        require(it.startMs == null || it.startMs >= 0) { "Invalid transcript start time" }
        require(it.endMs == null || it.endMs >= 0) { "Invalid transcript end time" }
        require(it.startMs == null || it.endMs == null || it.endMs >= it.startMs) { "Invalid transcript time range" }
    }
    snapshot.categories.forEach {
        validateBackupId(it.id)
        require(cleanCategoryName(it.name) == it.name) { "Invalid category name" }
        require(normalizedCategoryName(it.name) == it.normalizedName) { "Invalid normalized category name" }
    }
    val categoryIds = snapshot.categories.map { it.id }.toSet()
    val recordingIds = snapshot.recordings.map { it.recordingId }.toSet() + snapshot.segments.map { it.recordingId }
    val noteKeys = snapshot.dailyNotes.map { dailyNoteKey(it.dateKey) }.toSet() +
        recordingIds.map(::recordingNoteKey) + snapshot.chunks.map { recordingNoteKey("legacy:${it.dateKey}") }
    snapshot.organizations.forEach {
        validateNoteKey(it.noteKey)
        require(it.noteKey in noteKeys) { "A note annotation has no matching note or recording" }
        require(it.categoryId == null || it.categoryId in categoryIds) { "A note refers to a missing category" }
        require(cleanNoteTitle(it.title) == it.title) { "Invalid note title" }
        validateUserSummary(it.userSummary)
        require(it.updatedAtEpochMs >= 0) { "Invalid note timestamp" }
    }
}

fun validateBackupAudioFileName(name: String) {
    require(name.isNotBlank() && name.length <= 240 && name != "." && name != ".." &&
        name.none { it == '/' || it == '\\' || it == ':' || it.isISOControl() }) {
        "A backup audio filename must be a safe relative basename"
    }
}

/** Plans an additive merge. A recording already present locally is kept as one complete unit. */
fun planLibraryImport(current: LibrarySnapshot, incoming: LibrarySnapshot): LibraryImportPlan {
    validateLibrarySnapshot(incoming)
    val currentIds = current.recordings.map { it.recordingId }.toSet() + current.segments.map { it.recordingId }
    val incomingIds = incoming.recordings.map { it.recordingId }.toSet() + incoming.segments.map { it.recordingId }
    val addedIds = incomingIds - currentIds
    val recordings = incoming.recordings.filter { it.recordingId in addedIds }
    val segments = incoming.segments.filter { it.recordingId in addedIds }
    val existingDates = current.dailyNotes.map { it.dateKey }.toSet()
    val dailyNotes = incoming.dailyNotes.filter { it.dateKey !in existingDates }

    val categoriesByName = current.categories.associateBy { it.normalizedName }.toMutableMap()
    val categoryIds = current.categories.map { it.id }.toMutableSet()
    val categoryMapping = mutableMapOf<String, String>()
    val categories = incoming.categories.mapNotNull { category ->
        val existing = categoriesByName[category.normalizedName]
        if (existing != null) {
            categoryMapping[category.id] = existing.id
            null
        } else {
            var id = category.id
            var attempt = 0
            while (id in categoryIds) {
                id = UUID.nameUUIDFromBytes("restore-category:${category.id}:${category.normalizedName}:${attempt++}".toByteArray(Charsets.UTF_8)).toString()
            }
            category.copy(id = id).also {
                categoryIds += id
                categoriesByName[it.normalizedName] = it
                categoryMapping[category.id] = id
            }
        }
    }

    // Legacy IDs are device-local integers. Match immutable content with multiplicity, then
    // allocate a new ID only for a genuine collision; repeated restores never duplicate text.
    val currentByIdentity = current.chunks.groupBy(::legacyIdentity).mapValues { it.value.toMutableList() }.toMutableMap()
    val reservedIds = (current.chunks.map { it.id } + incoming.chunks.map { it.id }).toMutableSet()
    val usedIds = current.chunks.map { it.id }.toMutableSet()
    var nextId = reservedIds.maxOrNull() ?: 0L
    val chunks = incoming.chunks.sortedBy { it.id }.mapNotNull { chunk ->
        val matches = currentByIdentity[legacyIdentity(chunk)]
        if (!matches.isNullOrEmpty()) {
            val matchingId = matches.indexOfFirst { it.id == chunk.id }
            matches.removeAt(if (matchingId >= 0) matchingId else 0)
            null
        } else {
            var id = chunk.id
            if (id in usedIds) {
                require(nextId < Long.MAX_VALUE) { "No space remains for legacy transcript IDs" }
                id = ++nextId
            }
            usedIds += id
            chunk.copy(id = id)
        }
    }
    val currentKeys = current.organizations.map { it.noteKey }.toSet()
    val newLegacyDates = chunks.map { it.dateKey }.toSet() - current.chunks.map { it.dateKey }.toSet()
    val allowedKeys = addedIds.map(::recordingNoteKey).toSet() + dailyNotes.map { dailyNoteKey(it.dateKey) } +
        newLegacyDates.map { recordingNoteKey("legacy:$it") }
    val organizations = incoming.organizations.filter { it.noteKey !in currentKeys && it.noteKey in allowedKeys }
        .map { it.copy(categoryId = it.categoryId?.let(categoryMapping::getValue)) }

    return LibraryImportPlan(
        LibrarySnapshot(dailyNotes = dailyNotes, chunks = chunks, segments = segments, recordings = recordings,
            categories = categories, organizations = organizations),
        LibraryImportResult(
            recordingsAdded = addedIds.size, dailyNotesAdded = dailyNotes.size, legacyChunksAdded = chunks.size,
            segmentsAdded = segments.size, categoriesAdded = categories.size, organizationsAdded = organizations.size,
            recordingsSkipped = (incomingIds intersect currentIds).size,
            dailyNotesSkipped = incoming.dailyNotes.size - dailyNotes.size,
            legacyChunksSkipped = incoming.chunks.size - chunks.size, segmentsSkipped = incoming.segments.size - segments.size,
            categoriesReused = incoming.categories.size - categories.size,
            organizationsSkipped = incoming.organizations.size - organizations.size,
            importedRecordingIds = addedIds
        )
    )
}

private data class LegacyIdentity(val date: String, val text: String, val isFinal: Boolean, val timestamp: Long)
private fun legacyIdentity(chunk: TranscriptChunkEntity) = LegacyIdentity(chunk.dateKey, chunk.text, chunk.isFinal, chunk.createdAtEpochMs)
private fun <T> requireUnique(values: List<T>, label: String) = require(values.size == values.toSet().size) { "Backup contains duplicate $label" }
internal fun validateBackupDate(date: String) {
    require(runCatching { LocalDate.parse(date).toString() == date }.getOrDefault(false)) { "Invalid note date" }
}
private fun validateBackupId(id: String) {
    require(id.matches(Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,199}")) && id != "." && id != "..") { "Invalid backup ID" }
}
internal fun validateRecordingId(id: String) {
    validateBackupId(id)
    require(!id.startsWith("legacy:")) { "Invalid recording ID" }
}
