package com.sainadh.livenotes.desktop.data

import com.sainadh.livenotes.desktop.RecordingDocument

fun timestamp(ms: Long): String {
    val seconds = ms.coerceAtLeast(0) / 1000
    return if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
    else "%02d:%02d".format(seconds / 60, seconds % 60)
}

/** Export preserves text outside timed/speaker-assigned regions as well. */
fun exportTranscript(document: RecordingDocument): String {
    if (document.turns.isEmpty()) return document.text
    val names = document.speakers.associate { it.id to it.name }
    return buildString {
        var cursor = 0
        document.turns.sortedBy { it.startChar }.forEach { turn ->
            if (turn.startChar < cursor || turn.endChar > document.text.length) return@forEach
            val gap = document.text.substring(cursor, turn.startChar)
            if (gap.isNotBlank()) appendLine(gap)
            if (isNotEmpty() && last() != '\n') appendLine()
            turn.startMs?.let { append("[${timestamp(it)}] ") }
            append(if (turn.overlapping) "Overlapping speakers" else names[turn.speakerId] ?: "Unknown speaker")
            append(": ")
            append(document.text.substring(turn.startChar, turn.endChar))
            appendLine()
            cursor = turn.endChar
        }
        val remainder = document.text.substring(cursor)
        if (remainder.isNotBlank()) append(remainder)
    }
}

fun exportSummary(document: RecordingDocument): String = buildString {
    append(document.summary)
    if (document.actionItems.isNotEmpty()) {
        append("\n\nAction items\n")
        document.actionItems.forEach { append("• ").appendLine(it) }
    }
}.trim()
