package com.sainadh.livenotes.desktop.ui

import com.sainadh.livenotes.data.WordCue
import com.sainadh.livenotes.desktop.TranscriptTurn
import java.util.TreeSet

/** Prepared once for a document revision. Playback only performs binary searches over these lists. */
internal data class TranscriptBlock(
    val start: Int,
    val end: Int,
    val text: String,
    val words: List<WordCue>,
    val turn: TranscriptTurn?
)

internal fun transcriptBlocks(text: String, words: List<WordCue> = emptyList(), turns: List<TranscriptTurn> = emptyList()): List<TranscriptBlock> {
    if (text.isBlank()) return emptyList()
    val cuts = TreeSet<Int>().apply { add(0); add(text.length) }
    Regex("[^\\s\\p{Z}]+").findAll(text).forEachIndexed { index, match ->
        if (index > 0 && index % 45 == 0) cuts.add(match.range.first)
    }
    val orderedTurns = turns.filter { it.startChar >= 0 && it.endChar > it.startChar && it.endChar <= text.length }.sortedBy { it.startChar }
    orderedTurns.forEach { cuts.add(it.startChar); cuts.add(it.endChar) }
    val boundaries = cuts.toList()
    var firstWord = 0
    var firstTurn = 0
    return buildList {
        for (index in 0 until boundaries.lastIndex) {
            val start = boundaries[index]
            val end = boundaries[index + 1]
            while (firstWord < words.size && words[firstWord].endChar <= start) firstWord++
            while (firstTurn < orderedTurns.size && orderedTurns[firstTurn].endChar <= start) firstTurn++
            val turn = orderedTurns.getOrNull(firstTurn)?.takeIf { it.startChar <= start && it.endChar >= end }
            val blockWords = ArrayList<WordCue>()
            var nextWord = firstWord
            while (nextWord < words.size && words[nextWord].startChar < end) blockWords += words[nextWord++]
            add(TranscriptBlock(start, end, text.substring(start, end), blockWords, turn))
        }
    }
}

internal fun activeWordIndex(words: List<WordCue>, positionMs: Long): Int {
    var low = 0
    var high = words.lastIndex
    var candidate = -1
    while (low <= high) {
        val middle = (low + high) ushr 1
        if (words[middle].startMs <= positionMs) { candidate = middle; low = middle + 1 }
        else high = middle - 1
    }
    return candidate.takeIf { it >= 0 && positionMs < words[it].endMs } ?: -1
}

internal fun activeBlockIndex(blocks: List<TranscriptBlock>, word: WordCue?): Int {
    if (word == null) return -1
    var low = 0
    var high = blocks.lastIndex
    while (low <= high) {
        val middle = (low + high) ushr 1
        val block = blocks[middle]
        when {
            word.startChar < block.start -> high = middle - 1
            word.startChar >= block.end -> low = middle + 1
            else -> return middle
        }
    }
    return -1
}

internal fun durationLabel(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0) / 1_000
    return if (seconds >= 3_600) "%d:%02d:%02d".format(seconds / 3_600, seconds / 60 % 60, seconds % 60)
    else "%02d:%02d".format(seconds / 60, seconds % 60)
}
