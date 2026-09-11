package com.sainadh.livenotes.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sainadh.livenotes.audio.PlaybackState
import com.sainadh.livenotes.data.SavedRecording
import com.sainadh.livenotes.data.RecordingSegment
import com.sainadh.livenotes.data.RecordingAudioStatus
import com.sainadh.livenotes.data.WordCue
import com.sainadh.livenotes.data.recordingWordCues
import com.sainadh.livenotes.service.CapturePhase
import com.sainadh.livenotes.stt.TranscriptStatus
import com.sainadh.livenotes.stt.TranscriptUpdate
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val DeepGreen = Color(0xFF203632)
private val Green = Color(0xFF17695B)
private val Mint = Color(0xFFD2EAD7)
private val Pale = Color(0xFFF6F5F0)
private val Gray = Color(0xFF60716B)
private val Highlight = Color(0xFFCAE7CE)

fun recordingTime(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0) / 1_000
    return if (seconds >= 3_600) "%d:%02d:%02d".format(Locale.ROOT, seconds / 3_600, seconds / 60 % 60, seconds % 60)
    else "%02d:%02d".format(Locale.ROOT, seconds / 60, seconds % 60)
}

@Composable
private fun RecorderSurface(content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
fun RecorderHero(
    phase: CapturePhase,
    durationMs: Long,
    audioLevel: Float,
    activeEngine: String,
    selectedEngine: String,
    audioRoute: String,
    onToggle: () -> Unit
) {
    val active = phase == CapturePhase.RECORDING || phase == CapturePhase.PREPARING
    var levels by remember { mutableStateOf(List(48) { 0f }) }
    LaunchedEffect(durationMs, audioLevel, phase) {
        levels = if (phase == CapturePhase.RECORDING) levels.drop(1) + audioLevel.coerceIn(0f, 1f)
        else if (phase == CapturePhase.IDLE) List(48) { 0f } else levels
    }
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(30.dp), colors = CardDefaults.cardColors(containerColor = DeepGreen)) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(8.dp).background(if (active) Color(0xFFFFAE98) else Mint, CircleShape))
                Text(when (phase) {
                    CapturePhase.IDLE -> "YOUR NEXT GREAT CONVERSATION"
                    CapturePhase.PREPARING -> "PREPARING YOUR RECORDER"
                    CapturePhase.RECORDING -> "RECORDING LIVE"
                    CapturePhase.FINISHING -> "SAVING YOUR RECORDING"
                }, color = Mint, style = MaterialTheme.typography.labelMedium, letterSpacing = 1.sp)
            }
            Text(
                if (phase == CapturePhase.IDLE) "Make every\nword count." else recordingTime(durationMs),
                style = if (phase == CapturePhase.IDLE) MaterialTheme.typography.headlineLarge else MaterialTheme.typography.headlineLarge.copy(fontFamily = FontFamily.Monospace, fontSize = 42.sp, lineHeight = 48.sp),
                color = Color.White,
                modifier = Modifier.semantics { if (phase != CapturePhase.IDLE) contentDescription = "Recording duration ${recordingTime(durationMs)}" }
            )
            Canvas(Modifier.fillMaxWidth().height(62.dp).semantics { contentDescription = if (active) "Microphone audio level" else "Ready to record" }) {
                val gap = size.width / levels.size
                levels.forEachIndexed { index, value ->
                    val barHeight = (5f + value * (size.height - 5f)).coerceIn(5f, size.height)
                    drawLine(if (index >= levels.size - 5) Color(0xFFF8C6AF) else Mint.copy(alpha = 0.45f + index.toFloat() / levels.size * 0.5f),
                        Offset(gap * (index + 0.5f), (size.height - barHeight) / 2),
                        Offset(gap * (index + 0.5f), (size.height + barHeight) / 2),
                        strokeWidth = (gap * 0.4f).coerceAtLeast(2f), cap = StrokeCap.Round)
                }
            }
            Text(if (phase == CapturePhase.IDLE) selectedEngine else activeEngine.ifBlank { "Getting ready…" }, color = Mint, style = MaterialTheme.typography.bodySmall)
            if (active && audioRoute != "Not listening") Text(audioRoute, color = Mint, style = MaterialTheme.typography.bodySmall)
            if (phase == CapturePhase.PREPARING || phase == CapturePhase.FINISHING) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Mint, trackColor = Color.White.copy(alpha = 0.12f))
            }
            Button(
                onClick = onToggle, enabled = phase != CapturePhase.FINISHING,
                modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp), shape = RoundedCornerShape(20.dp),
                colors = ButtonDefaults.buttonColors(containerColor = if (active) Color(0xFFFFE8DC) else Mint, contentColor = DeepGreen,
                    disabledContainerColor = Color(0xFF415650), disabledContentColor = Color.White),
                contentPadding = PaddingValues(16.dp)
            ) {
                if (active) Box(Modifier.size(15.dp).background(DeepGreen, RoundedCornerShape(4.dp)))
                else Box(Modifier.size(16.dp).background(Green, CircleShape))
                Spacer(Modifier.width(10.dp))
                Text(when (phase) {
                    CapturePhase.IDLE -> "Start recording"
                    CapturePhase.PREPARING -> "Cancel preparation"
                    CapturePhase.RECORDING -> "Stop & save"
                    CapturePhase.FINISHING -> "Finishing…"
                })
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TextActions(text: String, onCopy: () -> Unit, onShare: () -> Unit, onExport: (() -> Unit)? = null, enabled: Boolean = text.isNotBlank()) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onCopy, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) { Text("Copy") }
        OutlinedButton(onClick = onShare, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) { Text("Share text") }
        if (onExport != null) TextButton(onClick = onExport, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) { Text("Save .txt") }
    }
}

@Composable
fun LiveTranscriptPanel(
    phase: CapturePhase,
    transcript: String,
    segments: List<TranscriptUpdate>,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onExport: () -> Unit,
    hasEarlierText: Boolean = false,
    onOpenFull: () -> Unit = {}
) {
    val listState = rememberLazyListState()
    val paragraphs = remember(transcript) { transcriptParagraphs(transcript) }
    val liveCues = remember(segments) {
        recordingWordCues(segments.map { RecordingSegment(it.segmentId, it.text, it.status, it.appendToPrevious, it.startMs, it.endMs) })
    }
    var followLive by rememberSaveable { mutableStateOf(true) }
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { if (it is DragInteraction.Start) followLive = false }
    }
    LaunchedEffect(transcript, followLive) {
        if (followLive && paragraphs.isNotEmpty()) listState.animateScrollToItem(paragraphs.lastIndex)
    }
    val latest = segments.lastOrNull { it.text.isNotBlank() }
    RecorderSurface {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Live transcript", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text(if (phase == CapturePhase.RECORDING) "● LIVE" else if (transcript.isNotBlank()) "LAST CAPTURE" else "READY",
                color = Green, style = MaterialTheme.typography.labelMedium)
        }
        if (transcript.isBlank()) {
            Text(when (phase) {
                CapturePhase.IDLE -> "Start a recording. Your words will appear here as you speak."
                CapturePhase.PREPARING -> "Preparing speech recognition…"
                CapturePhase.RECORDING -> "Listening for your first words…"
                CapturePhase.FINISHING -> "Waiting for the final words…"
            }, modifier = Modifier.heightIn(min = 90.dp), style = MaterialTheme.typography.bodyLarge, color = Gray)
        } else {
            LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().heightIn(min = 90.dp, max = 270.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                itemsIndexed(paragraphs) { index, paragraph ->
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        liveCues.firstOrNull { it.startChar >= paragraph.start && it.startChar < paragraph.end }?.let { cue ->
                            Text("≈ ${recordingTime(cue.startMs)}", style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace, color = Gray)
                        }
                        SelectionContainer {
                            val text = buildAnnotatedString {
                                append(paragraph.text)
                                if (index == paragraphs.lastIndex && phase == CapturePhase.RECORDING) {
                                    Regex("\\S+$").find(paragraph.text)?.range?.let { range ->
                                        addStyle(SpanStyle(background = Highlight, color = Green, fontWeight = FontWeight.SemiBold), range.first, range.last + 1)
                                    }
                                }
                            }
                            Text(text, style = MaterialTheme.typography.bodyLarge, color = DeepGreen)
                        }
                    }
                }
            }
            if (phase == CapturePhase.RECORDING) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (latest?.status == TranscriptStatus.PARTIAL) "Words are still being refined" else "Words update as you speak",
                        style = MaterialTheme.typography.bodySmall, color = Gray, modifier = Modifier.weight(1f))
                    if (!followLive) TextButton(onClick = { followLive = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Follow live") }
                }
            }
        }
        if (hasEarlierText) {
            Text("Showing recent words. Copy, share, and save include the full transcript.",
                style = MaterialTheme.typography.bodySmall, color = Gray)
            TextButton(onClick = onOpenFull, modifier = Modifier.heightIn(min = 48.dp)) { Text("View full transcript") }
        }
        TextActions(transcript, onCopy, onShare, onExport, enabled = hasEarlierText || transcript.isNotBlank())
    }
}

@Composable
fun RecordingLibraryCard(recording: SavedRecording, isPlaying: Boolean, playbackEnabled: Boolean, onOpen: () -> Unit, onPlay: () -> Unit) {
    val recordingInProgress = recording.audioStatus == RecordingAudioStatus.RECORDING
    val hasAudio = recording.audioFileName != null && recording.audioStatus == RecordingAudioStatus.READY
    RecorderSurface {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            Box(Modifier.size(44.dp).background(Mint, RoundedCornerShape(15.dp)), contentAlignment = Alignment.Center) {
                Text(if (hasAudio) "♫" else "T", color = Green, fontSize = 23.sp)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(recording.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(recordingDate(recording) + if (recording.durationMs > 0) " · ${recordingTime(recording.durationMs)}" else "", style = MaterialTheme.typography.bodySmall, color = Gray)
            }
        }
        Text(recording.text.ifBlank { if (recordingInProgress) "Listening for your words…" else "No transcript available" }, maxLines = 3, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium, color = if (recording.text.isBlank()) Gray else DeepGreen)
        if (recordingInProgress) Text("● Recording in progress", style = MaterialTheme.typography.bodySmall, color = Green)
        else if (!hasAudio) Text("Transcript only · Audio unavailable", style = MaterialTheme.typography.bodySmall, color = Gray)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (hasAudio) {
                Button(onClick = onPlay, enabled = playbackEnabled, modifier = Modifier.heightIn(min = 48.dp), shape = RoundedCornerShape(14.dp)) {
                    PlaybackGlyph(isPlaying)
                    Spacer(Modifier.width(8.dp))
                    Text(if (isPlaying) "Pause" else "Play")
                }
            }
            TextButton(onClick = onOpen, modifier = Modifier.heightIn(min = 48.dp)) { Text("Open recording") }
        }
    }
}

/** Full history is materialized only when requested, so incoming words cannot repeatedly lay it out. */
@Composable
fun TranscriptSnapshotScreen(
    text: String, captureActive: Boolean, onBack: () -> Unit, onRefresh: () -> Unit,
    onCopy: () -> Unit, onShare: () -> Unit, onExport: () -> Unit
) {
    val paragraphs = remember(text) { transcriptParagraphs(text) }
    Scaffold(containerColor = Pale, topBar = {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹  Back") }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onRefresh) { Text("Refresh transcript") }
        }
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)) {
            item { Text("Full transcript", style = MaterialTheme.typography.headlineLarge) }
            item { Text(if (captureActive) "Recording continues. Refresh to include the newest words, or go back to follow live."
                else "Transcript snapshot. Refresh to include the latest words.", style = MaterialTheme.typography.bodySmall, color = Gray) }
            item { TextActions(text, onCopy, onShare, onExport) }
            itemsIndexed(paragraphs, key = { _, paragraph -> paragraph.start }) { _, paragraph ->
                SelectionContainer { Text(paragraph.text, style = MaterialTheme.typography.bodyLarge) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RecordingDetailScreen(
    recording: SavedRecording,
    playback: PlaybackState,
    captureActive: Boolean,
    onBack: () -> Unit,
    onPlay: () -> Unit,
    onPlayFrom: (Long) -> Unit,
    onSeek: (Long) -> Unit,
    onSpeed: (Float) -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onShareAudio: () -> Unit,
    onExport: () -> Unit
) {
    val current = playback.recordingId == recording.recordingId
    val hasAudio = recording.audioFileName != null && recording.audioStatus == RecordingAudioStatus.READY
    val recordingInProgress = recording.audioStatus == RecordingAudioStatus.RECORDING
    val positionMs = if (current) playback.positionMs else 0L
    val durationMs = if (current && playback.durationMs > 0) playback.durationMs else recording.durationMs
    val playing = current && playback.isPlaying
    val cues = remember(recording) { recording.wordCues }
    val paragraphs = remember(recording.text) { transcriptParagraphs(recording.text) }
    val activeCue = cues.lastOrNull { positionMs >= it.startMs && positionMs < it.endMs }
    val activeParagraph = paragraphs.indexOfFirst { activeCue != null && activeCue.startChar >= it.start && activeCue.startChar < it.end }
    val listState = rememberLazyListState()
    var follow by rememberSaveable(recording.recordingId) { mutableStateOf(true) }
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { if (it is DragInteraction.Start) follow = false }
    }
    LaunchedEffect(activeParagraph, follow, playing) {
        if (follow && playing && activeParagraph >= 0) listState.animateScrollToItem(activeParagraph + 3)
    }
    Scaffold(
        containerColor = Pale,
        topBar = {
            Surface(color = Pale) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) { Text("‹  Back") }
                    Spacer(Modifier.weight(1f))
                    Text("RECORDING", style = MaterialTheme.typography.labelMedium, color = Gray, modifier = Modifier.padding(end = 12.dp))
                }
            }
        },
        bottomBar = {
            if (hasAudio) {
                PlaybackPanel(positionMs, durationMs, playing, playback.speed, if (current) playback.error else null, captureActive,
                    onPlay, onSeek, onSpeed)
            }
        }
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), state = listState,
            contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(recording.title, style = MaterialTheme.typography.headlineLarge)
                    Text(recordingDate(recording), style = MaterialTheme.typography.bodyMedium, color = Gray)
                    if (recordingInProgress) Text("● Recording in progress. Audio will be available after saving.", style = MaterialTheme.typography.bodySmall, color = Green)
                    if (recording.hasUnconfirmedWords) Text("Some words could not be confirmed. Check them against the audio.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
            item {
                Column {
                    TextActions(recording.text, onCopy, onShare, onExport)
                    if (hasAudio) TextButton(onClick = onShareAudio, modifier = Modifier.heightIn(min = 48.dp)) { Text("Share audio") }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Transcript", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                        if (cues.isNotEmpty() && hasAudio) {
                            TextButton(onClick = { follow = !follow }, modifier = Modifier.heightIn(min = 48.dp)
                                .semantics { stateDescription = if (follow) "Automatic transcript scrolling on" else "Automatic transcript scrolling off" }) {
                                Text(if (follow) "Following ✓" else "Follow audio")
                            }
                        }
                    }
                    Text(when {
                        recordingInProgress -> "Your transcript is updating. Stop and save the recording to listen back."
                        !hasAudio -> "This recording has no saved audio. You can still copy and share its transcript."
                        cues.isEmpty() -> "Word timing is unavailable for this recording. Use the player to listen."
                        cues.any { it.isEstimated } -> "Tap a word to listen from there. Word times are estimated; the highlight may differ from the audio."
                        else -> "Tap a word to listen from there. Highlights follow word times from the speech model."
                    }, color = Gray, style = MaterialTheme.typography.bodySmall)
                    if (recording.text.isBlank() && !recordingInProgress) Text(if (hasAudio) "No speech was transcribed. Your saved audio is available below." else "No transcript is available for this recording.", style = MaterialTheme.typography.bodyLarge, color = Gray)
                }
            }
            itemsIndexed(paragraphs, key = { _, paragraph -> paragraph.start }) { _, paragraph ->
                val paragraphCues = cues.filter { it.startChar >= paragraph.start && it.startChar < paragraph.end }
                TimedParagraph(paragraph, paragraphCues, activeCue, hasAudio && !captureActive, onPlayFrom)
            }
        }
    }
}

private data class TranscriptParagraph(val text: String, val start: Int, val end: Int)

/** Split long transcripts into manageable, lazily rendered blocks without changing character offsets. */
private fun transcriptParagraphs(text: String): List<TranscriptParagraph> {
    if (text.isBlank()) return emptyList()
    val words = Regex("\\S+").findAll(text).toList()
    return words.chunked(45).map { group ->
        val start = group.first().range.first
        val end = group.last().range.last + 1
        TranscriptParagraph(text.substring(start, end), start, end)
    }
}

@Composable
private fun TimedParagraph(paragraph: TranscriptParagraph, cues: List<WordCue>, activeCue: WordCue?, canSeek: Boolean, onSeek: (Long) -> Unit) {
    val firstCue = cues.firstOrNull()
    val active = activeCue != null && activeCue.startChar in paragraph.start until paragraph.end
    Column(Modifier.fillMaxWidth().background(if (active) Color.White else Color.Transparent, RoundedCornerShape(20.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (firstCue != null) {
            TextButton(onClick = { onSeek(firstCue.startMs) }, enabled = canSeek, contentPadding = PaddingValues(horizontal = 10.dp),
                modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Seek to ${if (firstCue.isEstimated) "approximately " else ""}${recordingTime(firstCue.startMs)}" }) {
                Text("${if (firstCue.isEstimated) "≈ " else ""}${recordingTime(firstCue.startMs)}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelMedium)
            }
        }
        if (cues.isEmpty() || !canSeek) {
            SelectionContainer { Text(paragraph.text, style = MaterialTheme.typography.bodyLarge) }
        } else {
            val styledText = buildAnnotatedString {
                append(paragraph.text)
                cues.forEach { cue ->
                    val start = (cue.startChar - paragraph.start).coerceIn(0, length)
                    val end = (cue.endChar - paragraph.start).coerceIn(start, length)
                    if (end > start) {
                        addStringAnnotation("seek", cue.startMs.toString(), start, end)
                        if (cue == activeCue) addStyle(SpanStyle(background = Highlight, color = DeepGreen, fontWeight = FontWeight.Bold), start, end)
                        else if (activeCue != null && cue.endMs <= activeCue.startMs) addStyle(SpanStyle(color = Gray), start, end)
                    }
                }
            }
            ClickableText(styledText, style = MaterialTheme.typography.bodyLarge.copy(color = DeepGreen),
                onClick = { offset -> styledText.getStringAnnotations("seek", offset, offset).firstOrNull()?.item?.toLongOrNull()?.let(onSeek) },
                modifier = Modifier.fillMaxWidth())
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlaybackPanel(
    positionMs: Long, durationMs: Long, isPlaying: Boolean, speed: Float, error: String?, captureActive: Boolean,
    onPlay: () -> Unit, onSeek: (Long) -> Unit, onSpeed: (Float) -> Unit
) {
    var dragPosition by remember { mutableStateOf<Float?>(null) }
    var speedMenu by remember { mutableStateOf(false) }
    val safeDuration = durationMs.coerceAtLeast(1)
    Surface(color = Color.White, shadowElevation = 12.dp, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (error != null) Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            if (captureActive) Text("Stop recording to play saved audio.", style = MaterialTheme.typography.bodySmall, color = Gray)
            Slider(value = dragPosition ?: positionMs.toFloat().coerceIn(0f, safeDuration.toFloat()),
                onValueChange = { dragPosition = it }, onValueChangeFinished = { dragPosition?.let { onSeek(it.toLong()) }; dragPosition = null },
                valueRange = 0f..safeDuration.toFloat(), enabled = !captureActive && durationMs > 0,
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Playback position"; stateDescription = "${recordingTime(positionMs)} of ${recordingTime(durationMs)}" })
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(recordingTime(dragPosition?.toLong() ?: positionMs), style = MaterialTheme.typography.labelMedium, color = Green, fontFamily = FontFamily.Monospace)
                Text(recordingTime(durationMs), style = MaterialTheme.typography.labelMedium, color = Gray, fontFamily = FontFamily.Monospace)
            }
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                TextButton(onClick = { onSeek((positionMs - 10_000).coerceAtLeast(0)) }, enabled = !captureActive,
                    modifier = Modifier.heightIn(min = 56.dp).semantics { contentDescription = "Rewind 10 seconds" }) { Text("−10s") }
                Button(onClick = onPlay, enabled = !captureActive, shape = CircleShape, modifier = Modifier.size(64.dp)
                    .semantics { contentDescription = if (isPlaying) "Pause recording" else "Play recording" }, contentPadding = PaddingValues(20.dp)) {
                    PlaybackGlyph(isPlaying, Modifier.size(22.dp))
                }
                TextButton(onClick = { onSeek((positionMs + 10_000).coerceAtMost(durationMs)) }, enabled = !captureActive && durationMs > 0,
                    modifier = Modifier.heightIn(min = 56.dp).semantics { contentDescription = "Forward 10 seconds" }) { Text("+10s") }
                Box {
                    TextButton(onClick = { speedMenu = true }, enabled = !captureActive,
                        modifier = Modifier.heightIn(min = 56.dp).semantics { contentDescription = "Playback speed $speed times" }) { Text("${speed}×") }
                    DropdownMenu(expanded = speedMenu, onDismissRequest = { speedMenu = false }) {
                        listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f).forEach { value ->
                            DropdownMenuItem(text = { Text("${value}×" + if (value == speed) " ✓" else "") }, onClick = { onSpeed(value); speedMenu = false })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaybackGlyph(pausedIcon: Boolean, modifier: Modifier = Modifier.size(16.dp)) {
    val color = MaterialTheme.colorScheme.onPrimary
    Canvas(modifier) {
        if (pausedIcon) {
            drawLine(color, Offset(size.width * .3f, 0f), Offset(size.width * .3f, size.height), size.width * .22f, StrokeCap.Round)
            drawLine(color, Offset(size.width * .7f, 0f), Offset(size.width * .7f, size.height), size.width * .22f, StrokeCap.Round)
        } else drawPath(Path().apply { moveTo(size.width * .15f, 0f); lineTo(size.width, size.height / 2); lineTo(size.width * .15f, size.height); close() }, color)
    }
}

private fun recordingDate(recording: SavedRecording): String = runCatching {
    Instant.ofEpochMilli(recording.startedAtEpochMs).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("EEE, MMM d · HH:mm", Locale.getDefault()))
}.getOrDefault(recording.dateKey)
