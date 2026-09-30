package com.sainadh.livenotes.data

import java.text.Normalizer
import java.util.Locale

/** User annotations are independent of transcript and generated summary updates. */
data class NoteOrganization(
    val noteKey: String,
    val title: String = "",
    val isBookmarked: Boolean = false,
    val categoryId: String? = null,
    val userSummary: String = "",
    val updatedAtEpochMs: Long = 0L
)

data class NoteCategory(val id: String, val name: String)

fun recordingNoteKey(id: String): String {
    require(id.isNotBlank()) { "Recording ID cannot be blank" }
    return "recording:$id"
}

fun dailyNoteKey(date: String): String {
    require(date.isNotBlank()) { "Note date cannot be blank" }
    return "daily:$date"
}

internal fun validateNoteKey(noteKey: String) {
    require((noteKey.startsWith("recording:") && noteKey.removePrefix("recording:").isNotBlank()) ||
        (noteKey.startsWith("daily:") && noteKey.removePrefix("daily:").isNotBlank())) {
        "Select a recording or daily note"
    }
}

internal fun cleanNoteTitle(title: String): String = title.trim().also {
    require(it.length <= 120) { "Use a title with 120 characters or fewer" }
    require(it.none(Char::isISOControl)) { "Use a single line for the title" }
}

internal fun cleanCategoryName(name: String): String = name.trim().also {
    require(it.isNotBlank()) { "Enter a category name" }
    require(it.length <= 60) { "Use a category name with 60 characters or fewer" }
    require(it.none(Char::isISOControl)) { "Use a single line for the category name" }
}

internal fun normalizedCategoryName(name: String): String =
    Normalizer.normalize(name, Normalizer.Form.NFKC).lowercase(Locale.ROOT)

internal fun validateUserSummary(summary: String) {
    require(summary.length <= 100_000) { "Use a summary with 100,000 characters or fewer" }
}
