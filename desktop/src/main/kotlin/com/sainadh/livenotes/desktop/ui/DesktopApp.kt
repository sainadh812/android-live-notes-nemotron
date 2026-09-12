package com.sainadh.livenotes.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sainadh.livenotes.ai.LlmProvider
import com.sainadh.livenotes.data.WordCue
import com.sainadh.livenotes.desktop.*
import com.sainadh.livenotes.stt.SpeechLanguage
import com.sainadh.livenotes.stt.SpeechModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val Paper = Color(0xFFF6F5EF)
private val Ink = Color(0xFF203C36)
private val Teal = Color(0xFF216B5C)
private val Muted = Color(0xFF65756F)
private val Line = Color(0xFFE1E6DE)
private val Mint = Color(0xFFD2E9CE)
private val Wash = Color(0xFFEBF1E8)
private val Accent = Color(0xFFDAEF91)
private val Error = Color(0xFF9A403C)
private val SpeakerColors = listOf(Teal, Color(0xFF76619A), Color(0xFF9C6B34), Color(0xFF3A7290), Color(0xFF9C5870))

private enum class Page(val title: String, val icon: ImageVector) {
    RECORD("Record", Icons.Default.Mic), LIBRARY("Library", Icons.Default.LibraryBooks), SETTINGS("Settings", Icons.Default.Settings)
}
private data class Confirmation(val title: String, val message: String, val confirm: String, val action: () -> Unit)
private data class NameEdit(val title: String, val initial: String, val maxLength: Int, val save: (String) -> Unit)

@Composable
fun DesktopApp(state: AppState, actions: DesktopActions) {
    var page by remember { mutableStateOf(Page.RECORD) }
    var confirmation by remember { mutableStateOf<Confirmation?>(null) }
    var nameEdit by remember { mutableStateOf<NameEdit?>(null) }
    MaterialTheme(
        colorScheme = lightColorScheme(primary = Teal, onPrimary = Color.White, primaryContainer = Mint,
            onPrimaryContainer = Ink, secondary = Teal, background = Paper, onBackground = Ink,
            surface = Color.White, onSurface = Ink, surfaceVariant = Wash, onSurfaceVariant = Muted,
            outline = Line, error = Error),
        typography = Typography(bodyLarge = androidx.compose.ui.text.TextStyle(fontSize = 16.sp, lineHeight = 26.sp),
            bodyMedium = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, lineHeight = 22.sp))
    ) {
        if (state.initializing) {
            Box(Modifier.fillMaxSize().background(Paper), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    Icon(Icons.Default.GraphicEq, null, tint = Teal, modifier = Modifier.size(44.dp))
                    Text("Live Meeting Notes", color = Ink, fontSize = 25.sp, fontWeight = FontWeight.SemiBold)
                    CircularProgressIndicator(Modifier.size(25.dp), color = Teal, strokeWidth = 2.dp)
                    Text("Opening your library…", color = Muted)
                }
            }
            return@MaterialTheme
        }
        Row(Modifier.fillMaxSize().background(Paper).testTag("desktop-app")) {
            Sidebar(page, state, onPage = { page = it })
            Column(Modifier.fillMaxSize()) {
                state.error?.let { Banner(it, true, actions::dismissError) }
                state.notice?.let { Banner(it, false, actions::dismissNotice) }
                when (page) {
                    Page.RECORD -> RecordPage(state, actions, onSettings = { page = Page.SETTINGS }, onLibrary = { page = Page.LIBRARY })
                    Page.LIBRARY -> if (state.selected != null) RecordingPage(state, actions,
                        onRename = { doc -> nameEdit = NameEdit("Rename recording", doc.entry.title, 120) { actions.renameRecording(doc.entry.id, it) } },
                        onRenameSpeaker = { doc, speaker -> nameEdit = NameEdit("Rename speaker", speaker.name, 80) { actions.renameSpeaker(doc.entry.id, speaker.id, it) } },
                        onDelete = { doc -> confirmation = Confirmation("Delete this recording?", "“${doc.entry.title}” and its audio, transcript, speaker labels, and notes will be removed from this computer.", "Delete recording") { actions.deleteRecording(doc.entry.id) } },
                        onSettings = { page = Page.SETTINGS })
                    else LibraryPage(state, actions, onRecord = { page = Page.RECORD })
                    Page.SETTINGS -> SettingsPage(state, actions, onRemove = { model ->
                        confirmation = Confirmation("Remove ${model.title}?", "This removes the downloaded speech model. Your recordings and notes stay in the library.", "Remove model") { actions.removeModel(model.id) }
                    }, onDeleteKey = {
                        confirmation = Confirmation("Remove your API key?", "New AI summaries will need a key. Your saved recordings and summaries will remain available.", "Remove key", actions::deleteApiKey)
                    })
                }
            }
        }
        state.liveFullText?.let { FullTranscriptDialog(it, state.capture.active, actions) }
        confirmation?.let { request ->
            AlertDialog(onDismissRequest = { confirmation = null }, title = { Text(request.title) },
                text = { Text(request.message) }, confirmButton = {
                    TextButton(onClick = { confirmation = null; request.action() }) { Text(request.confirm, color = Error) }
                }, dismissButton = { TextButton(onClick = { confirmation = null }) { Text("Cancel") } })
        }
        nameEdit?.let { request ->
            var value by remember(request) { mutableStateOf(request.initial) }
            AlertDialog(onDismissRequest = { nameEdit = null }, title = { Text(request.title) }, text = {
                OutlinedTextField(value, { value = it.take(request.maxLength) }, singleLine = true,
                    label = { Text("Name") }, modifier = Modifier.fillMaxWidth().testTag("name-field"))
            }, confirmButton = { TextButton(onClick = { request.save(value.trim()); nameEdit = null }, enabled = value.isNotBlank()) { Text("Save name") } },
                dismissButton = { TextButton(onClick = { nameEdit = null }) { Text("Cancel") } })
        }
    }
}

@Composable
private fun Sidebar(page: Page, state: AppState, onPage: (Page) -> Unit) {
    Column(Modifier.width(190.dp).fillMaxHeight().background(Color(0xFFEDF0E7)).padding(horizontal = 18.dp, vertical = 28.dp)) {
        Box(Modifier.size(44.dp).background(Teal, RoundedCornerShape(15.dp)), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.GraphicEq, null, tint = Accent, modifier = Modifier.size(28.dp))
        }
        Spacer(Modifier.height(14.dp))
        Text("Live Meeting\nNotes", fontSize = 23.sp, lineHeight = 27.sp, fontWeight = FontWeight.SemiBold, color = Ink)
        Text("A little more present.", style = MaterialTheme.typography.bodySmall, color = Muted, modifier = Modifier.padding(top = 8.dp))
        Spacer(Modifier.height(38.dp))
        Page.entries.forEach { entry ->
            val selected = entry == page
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (selected) Teal else Color.Transparent)
                .clickable { onPage(entry) }.padding(14.dp).testTag("nav-${entry.name.lowercase()}"), verticalAlignment = Alignment.CenterVertically) {
                Icon(entry.icon, null, tint = if (selected) Color.White else Muted, modifier = Modifier.size(21.dp))
                Spacer(Modifier.width(12.dp))
                Text(entry.title, color = if (selected) Color.White else Ink, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
            }
            Spacer(Modifier.height(7.dp))
        }
        Spacer(Modifier.weight(1f))
        if (state.capture.active) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).background(Teal, CircleShape)); Spacer(Modifier.width(7.dp))
                Text("Recording · ${durationLabel(state.capture.durationMs)}", color = Teal, style = MaterialTheme.typography.labelMedium)
            }
            Spacer(Modifier.height(16.dp))
        }
        HorizontalDivider(color = Line)
        Spacer(Modifier.height(16.dp))
        Icon(Icons.Default.Computer, null, tint = Muted, modifier = Modifier.size(18.dp))
        Text("Your audio stays here.", color = Ink, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 9.dp))
        Text("Local recording & speech.\nAI summaries are optional.", color = Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 5.dp))
    }
}

@Composable
private fun Banner(message: String, error: Boolean, dismiss: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(if (error) Color(0xFFFBEAE6) else Mint).padding(start = 24.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(message, color = if (error) Error else Ink, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        IconButton(onClick = dismiss) { Icon(Icons.Default.Close, "Dismiss ${if (error) "error" else "notice"}") }
    }
}

@Composable
private fun PageTitle(kicker: String, title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(kicker.uppercase(Locale.ROOT), color = Teal, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.8.sp)
        Text(title, fontSize = 32.sp, lineHeight = 38.sp, fontWeight = FontWeight.SemiBold)
        Text(subtitle, color = Muted, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun CardSection(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), color = Color.White, shape = RoundedCornerShape(22.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Line)) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
    }
}

@Composable
private fun RecordPage(state: AppState, actions: DesktopActions, onSettings: () -> Unit, onLibrary: () -> Unit) {
    val capture = state.capture
    val model = SpeechModel.fromId(state.settings.modelId) ?: SpeechModel.NEMOTRON_ENGLISH
    val ready = state.downloads.any { it.modelId == model.id && it.installed }
    val busyWithSpeakers = state.speakerJob.active || state.speakerJob.installing
    val previewBlocks = remember(capture.preview) { transcriptBlocks(capture.preview) }
    val transcriptScroll = rememberLazyListState()
    var follow by remember { mutableStateOf(true) }
    LaunchedEffect(transcriptScroll) { transcriptScroll.interactionSource.interactions.collect { if (it is DragInteraction.Start) follow = false } }
    LaunchedEffect(capture.preview, follow) { if (follow && previewBlocks.isNotEmpty()) transcriptScroll.scrollToItem(previewBlocks.lastIndex) }
    LazyColumn(Modifier.fillMaxSize().testTag("record-page"), contentPadding = PaddingValues(32.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        item { PageTitle("Make room for the conversation", "Be here. We’ll take notes.", "Record the room, follow the words, return to any moment.") }
        item {
            Surface(color = Ink, shape = RoundedCornerShape(26.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(28.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(when (capture.phase) { CapturePhase.IDLE -> "READY WHEN YOU ARE"; CapturePhase.PREPARING -> "GETTING READY"; CapturePhase.RECORDING -> "LISTENING TO YOUR MEETING"; CapturePhase.SAVING -> "SAVING YOUR MEETING" }, color = Mint, fontSize = 11.sp, letterSpacing = 1.5.sp)
                            Text(durationLabel(capture.durationMs), color = Color.White, fontSize = 56.sp, fontWeight = FontWeight.Light, fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(top = 12.dp).testTag("record-timer"))
                        }
                        LevelDisplay(capture.level, capture.phase == CapturePhase.RECORDING, Modifier.width(220.dp).height(85.dp))
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = if (capture.active) actions::stopRecording else actions::startRecording,
                            enabled = capture.phase != CapturePhase.SAVING && (capture.active || (ready && !busyWithSpeakers && !state.audioCheck.active)),
                            colors = ButtonDefaults.buttonColors(containerColor = if (capture.active) Color(0xFFFFDFCE) else Accent, contentColor = Ink,
                                disabledContainerColor = Color.White.copy(alpha = 0.12f), disabledContentColor = Mint.copy(alpha = 0.7f)),
                            shape = RoundedCornerShape(15.dp), contentPadding = PaddingValues(horizontal = 22.dp, vertical = 16.dp)) {
                            Icon(if (capture.active) Icons.Default.Stop else Icons.Default.Mic, null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(10.dp))
                            Text(when (capture.phase) { CapturePhase.IDLE -> "Start recording"; CapturePhase.PREPARING -> "Cancel preparation"; CapturePhase.RECORDING -> "Stop & save"; CapturePhase.SAVING -> "Saving…" }, fontWeight = FontWeight.SemiBold)
                        }
                        Column(Modifier.weight(1f)) {
                            Text(model.title, color = Color.White, style = MaterialTheme.typography.bodyMedium)
                            Text("${SpeechLanguage.fromCode(state.settings.languageCode).label} · On this computer", color = Mint, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    val transcriptLag = (capture.durationMs - capture.transcribedMs).coerceAtLeast(0)
                    if (capture.phase == CapturePhase.PREPARING) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Accent, trackColor = Teal)
                    if (capture.phase == CapturePhase.SAVING) {
                        if (capture.durationMs > 0 && transcriptLag > 0) {
                            LinearProgressIndicator(progress = (capture.transcribedMs.toFloat() / capture.durationMs).coerceIn(0f, 1f),
                                modifier = Modifier.fillMaxWidth(), color = Accent, trackColor = Teal)
                            Text("Finishing transcript · ${durationLabel(capture.transcribedMs)} of ${durationLabel(capture.durationMs)} processed",
                                color = Mint, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("transcription-progress"))
                        } else {
                            LinearProgressIndicator(Modifier.fillMaxWidth(), color = Accent, trackColor = Teal)
                            Text("Finalizing your transcript and word timings…", color = Mint, style = MaterialTheme.typography.bodySmall)
                        }
                    } else if (capture.phase == CapturePhase.RECORDING && transcriptLag > 2_000) {
                        Text("Recording continues · transcript is ${durationLabel(transcriptLag)} behind",
                            color = Accent, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("transcription-progress"))
                        Text("Audio keeps being saved. Transcription will catch up after you stop recording.", color = Mint, style = MaterialTheme.typography.bodySmall)
                    }
                    if (!ready && !capture.active) TextButton(onClick = onSettings, colors = ButtonDefaults.textButtonColors(contentColor = Accent)) { Text("Choose or download a speech model in Settings →") }
                    if (busyWithSpeakers && !capture.active) Text("Finish or cancel speaker processing before starting another recording.", color = Mint, style = MaterialTheme.typography.bodySmall)
                    if (state.audioCheck.active) Text("Finish the audio test in Settings before recording.", color = Mint, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item { MicrophonePicker(state, actions) }
        if (state.speakerJob.active || state.speakerJob.installing) item { SpeakerProgress(state.speakerJob, actions) }
        item {
            CardSection {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Live transcript", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Pill(if (capture.phase == CapturePhase.RECORDING) "● LIVE" else "TRANSCRIPT", Wash, Teal)
                }
                if (previewBlocks.isEmpty()) {
                    Column(Modifier.fillMaxWidth().height(110.dp), verticalArrangement = Arrangement.Center) {
                        Text(if (capture.active) "Listening for your first words…" else "Your conversation will appear here.", color = Muted, fontSize = 17.sp)
                        Text("Words update as you speak. Live timing is approximate.", color = Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                    }
                } else {
                    LazyColumn(Modifier.fillMaxWidth().heightIn(min = 100.dp, max = 230.dp), state = transcriptScroll, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        itemsIndexed(previewBlocks, key = { _, block -> block.start }) { index, block ->
                            val styled = remember(block.text, index == previewBlocks.lastIndex, capture.phase) {
                                buildAnnotatedString {
                                    append(block.text)
                                    if (index == previewBlocks.lastIndex && capture.phase == CapturePhase.RECORDING) Regex("\\S+\\s*$").find(block.text)?.let {
                                        addStyle(SpanStyle(background = Accent, color = Ink), it.range.first, it.range.last + 1)
                                    }
                                }
                            }
                            SelectionContainer { Text(styled, style = MaterialTheme.typography.bodyLarge) }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (capture.hasEarlierText) "Recent words shown. The complete transcript is kept." else "Live timing is approximate; final word timing is added after saving.",
                            modifier = Modifier.weight(1f), color = Muted, style = MaterialTheme.typography.bodySmall)
                        if (!follow && capture.active) TextButton(onClick = { follow = true }) { Text("Follow live") }
                    }
                }
                val hasText = capture.preview.isNotBlank() || capture.hasEarlierText
                ActionRow(onCopy = { actions.copyTranscript(null) }, onExport = { actions.exportTranscript(null) }, enabled = hasText) {
                    TextButton(onClick = actions::openFullTranscript, enabled = hasText) { Text("Full transcript") }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("After saving, speakers can be identified separately to keep live recording responsive.", color = Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                TextButton(onClick = onLibrary) { Text("Open library →") }
            }
        }
    }
}

@Composable
private fun LevelDisplay(level: Float, active: Boolean, modifier: Modifier) {
    val samples = remember { mutableStateListOf<Float>() }
    LaunchedEffect(level, active) {
        if (!active) samples.clear()
        else { samples += if (level.isFinite()) level.coerceIn(0f, 1f) else 0f; while (samples.size > 38) samples.removeAt(0) }
    }
    Canvas(modifier.semantics { contentDescription = "Microphone level" }) {
        val gap = size.width / 38
        repeat(38) { index ->
            val amplitude = samples.getOrNull(index - (38 - samples.size)) ?: 0f
            val height = (size.height * amplitude).coerceAtLeast(4f)
            drawLine(if (active) Accent else Mint.copy(alpha = .35f), Offset(gap * (index + .5f), (size.height - height) / 2),
                Offset(gap * (index + .5f), (size.height + height) / 2), gap * .42f, StrokeCap.Round)
        }
    }
}

@Composable
private fun MicrophonePicker(state: AppState, actions: DesktopActions) {
    val choices = deviceChoices("System default microphone", state.settings.microphoneId, state.microphones)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(Icons.Default.Mic, null, tint = Muted, modifier = Modifier.size(20.dp))
        ChoicePicker("Microphone", state.settings.microphoneId, choices, enabled = !state.capture.active && !state.audioCheck.active,
            modifier = Modifier.weight(1f), onSelect = { actions.updateSettings(state.settings.copy(microphoneId = it)) })
        IconButton(onClick = actions::refreshMicrophones, enabled = !state.capture.active && !state.audioCheck.active) { Icon(Icons.Default.Refresh, "Refresh audio devices") }
    }
}

private fun deviceChoices(default: String, selected: String, devices: List<Microphone>): List<Pair<String, String>> =
    listOf("" to default) + devices.filter { it.id.isNotEmpty() }.map { it.id to it.name } +
        if (selected.isNotBlank() && devices.none { it.id == selected }) listOf(selected to "Unavailable — choose another device") else emptyList()

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AudioSettings(state: AppState, actions: DesktopActions) {
    val checking = state.audioCheck.active
    val editable = !state.capture.active && !checking
    CardSection {
        SectionHeading(Icons.Default.Mic, "Microphone & speakers")
        MicrophonePicker(state, actions)
        ChoicePicker("Speakers / headphones", state.settings.outputDeviceId,
            deviceChoices("System default speakers", state.settings.outputDeviceId, state.outputDevices),
            enabled = editable && !state.playback.playing,
            onSelect = { actions.updateSettings(state.settings.copy(outputDeviceId = it)) })
        Text("Test your devices before the meeting. Microphone tests last up to 10 seconds; test audio is not saved or transcribed.", color = Muted, style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = actions::testMicrophone, enabled = editable && !state.playback.playing,
                modifier = Modifier.testTag("test-microphone")) { Text("Test microphone") }
            OutlinedButton(onClick = actions::testSpeakers, enabled = editable && !state.playback.playing,
                modifier = Modifier.testTag("test-speakers")) { Text("Test speakers") }
            if (checking) TextButton(onClick = actions::stopAudioTest) { Text("Stop test") }
        }
        if (checking && state.audioCheck.kind == "microphone") {
            LinearProgressIndicator(progress = state.audioCheck.level.coerceIn(0f, 1f), modifier = Modifier.fillMaxWidth(), color = Teal, trackColor = Wash)
        }
        if (state.audioCheck.message.isNotBlank()) Text(state.audioCheck.message, color = Teal, style = MaterialTheme.typography.bodySmall)
        if (state.microphoneAccess.supported) {
            Text(state.microphoneAccess.message, color = Muted, style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TextButton(onClick = actions::openMicrophoneSettings) { Text("Windows microphone settings") }
                TextButton(onClick = actions::openSoundSettings) { Text("Windows Sound settings") }
            }
        }
        Text("Only microphone audio is recorded. The speakers choice controls meeting playback; it does not capture another app’s sound.", color = Muted, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun LibraryPage(state: AppState, actions: DesktopActions, onRecord: () -> Unit) {
    var search by remember { mutableStateOf("") }
    val entries = remember(state.recordings, search) { state.recordings.filter { it.title.contains(search, true) || it.preview.contains(search, true) } }
    LazyColumn(Modifier.fillMaxSize().testTag("library-page"), contentPadding = PaddingValues(32.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item { PageTitle("All the moments worth keeping", "Your meeting library", "Listen again, find the right words, and turn conversations into next steps.") }
        if (state.capture.active) item { CardSection { Text("Recording in progress", fontWeight = FontWeight.SemiBold); Text("Your meeting will appear here after saving. You can keep following its transcript on Record.", color = Muted); TextButton(onClick = onRecord) { Text("Go to live recording") } } }
        item {
            OutlinedTextField(search, { search = it }, label = { Text("Find a recording") }, leadingIcon = { Icon(Icons.Default.Search, null) },
                singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp))
        }
        if (state.loadingRecording) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (entries.isEmpty()) item {
            CardSection {
                Icon(Icons.Default.LibraryBooks, null, tint = Teal, modifier = Modifier.size(36.dp))
                Text(if (search.isEmpty()) "The beginning of a useful library." else "No matching recordings", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                Text(if (search.isEmpty()) "Your saved audio, transcript, speakers, and notes will live together here." else "Try a different title or phrase from the recording preview.", color = Muted)
                if (search.isEmpty()) Button(onClick = onRecord) { Text("Make a recording") }
            }
        }
        items(entries, key = { it.id }) { entry ->
            Surface(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).clickable(enabled = !state.loadingRecording) { actions.selectRecording(entry.id) },
                color = Color.White, shape = RoundedCornerShape(20.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Line)) {
                Row(Modifier.padding(22.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    Box(Modifier.size(52.dp).background(Wash, RoundedCornerShape(17.dp)), contentAlignment = Alignment.Center) { Icon(if (entry.hasAudio) Icons.Default.GraphicEq else Icons.Default.Description, null, tint = Teal) }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(entry.title, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("${dateLabel(entry.startedAtMs)} · ${durationLabel(entry.durationMs)}" + if (entry.speakerCount > 0) " · ${entry.speakerCount} speakers" else "", color = Muted, style = MaterialTheme.typography.bodySmall)
                        Text(entry.preview.ifBlank { if (entry.hasAudio) "Audio recording · No transcript" else "No transcript available" }, color = Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (entry.interrupted) Text("Interrupted capture · Check the final words against the audio", color = Error, style = MaterialTheme.typography.bodySmall)
                    }
                    Icon(Icons.Default.ChevronRight, "Open recording", tint = Teal)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RecordingPage(state: AppState, actions: DesktopActions, onRename: (RecordingDocument) -> Unit,
    onRenameSpeaker: (RecordingDocument, SpeakerName) -> Unit, onDelete: (RecordingDocument) -> Unit, onSettings: () -> Unit) {
    val document = state.selected ?: return
    val entry = document.entry
    val player = state.playback
    val isCurrent = player.recordingId == entry.id
    val playing = isCurrent && player.playing
    val position = if (isCurrent) player.positionMs else 0L
    val duration = if (isCurrent && player.durationMs > 0) player.durationMs else entry.durationMs
    val canPlay = entry.hasAudio && !state.capture.active && !state.speakerJob.active && !state.speakerJob.installing
    val canAnalyze = entry.hasAudio && !state.capture.active && !state.speakerJob.active && !state.speakerJob.installing
    val blocks = remember(document.text, document.words, document.turns) { transcriptBlocks(document.text, document.words, document.turns) }
    val approximateTiming = remember(document.words) { document.words.any { it.isEstimated } }
    val names = remember(document.speakers) { document.speakers.associate { it.id to it.name } }
    val cue = document.words.getOrNull(activeWordIndex(document.words, position))
    val activeBlock = activeBlockIndex(blocks, cue)
    val scroll = rememberLazyListState()
    var follow by remember(entry.id) { mutableStateOf(true) }
    var tab by remember(entry.id) { mutableStateOf(0) }
    var analyzeDialog by remember(entry.id) { mutableStateOf(false) }
    var speakerCount by remember(entry.id) { mutableStateOf("auto") }
    var mergeSource by remember(entry.id) { mutableStateOf<SpeakerName?>(null) }
    LaunchedEffect(scroll) { scroll.interactionSource.interactions.collect { if (it is DragInteraction.Start) follow = false } }
    LaunchedEffect(activeBlock, follow, playing, tab) { if (follow && playing && activeBlock >= 0 && tab == 0) scroll.animateScrollToItem(activeBlock + 1) }
    val playFrom: (Long) -> Unit = actions::playFrom
    Column(Modifier.fillMaxSize().testTag("recording-detail")) {
        Column(Modifier.padding(start = 30.dp, end = 30.dp, top = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = actions::closeRecording) { Icon(Icons.Default.ArrowBack, null, modifier = Modifier.size(17.dp)); Spacer(Modifier.width(7.dp)); Text("Library") }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { onRename(document) }, enabled = !state.capture.active) { Icon(Icons.Default.Edit, "Rename recording") }
                IconButton(onClick = { onDelete(document) }, enabled = !state.capture.active && !state.speakerJob.active && !state.summaryBusy) { Icon(Icons.Default.DeleteOutline, "Delete recording", tint = Error) }
            }
            Text(entry.title, fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("${dateLabel(entry.startedAtMs)} · ${durationLabel(entry.durationMs)}" + if (!entry.hasAudio) " · Transcript only" else "", style = MaterialTheme.typography.bodySmall, color = Muted)
            if (entry.interrupted) Text("This capture was interrupted. Its available audio and words have been preserved.", color = Error, style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { actions.copyTranscript(entry.id) }, enabled = document.text.isNotBlank()) { Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(7.dp)); Text("Copy transcript") }
                TextButton(onClick = { actions.exportTranscript(entry.id) }, enabled = document.text.isNotBlank()) { Text("Save transcript") }
                TextButton(onClick = { actions.exportAudio(entry.id) }, enabled = entry.hasAudio) { Text("Save audio") }
            }
            TabRow(tab, containerColor = Paper, contentColor = Teal) {
                listOf("Transcript", "Speakers", "Summary & actions").forEachIndexed { index, label -> Tab(selected = tab == index, onClick = { tab = index }, text = { Text(label) }) }
            }
        }
        when (tab) {
            0 -> LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = scroll, contentPadding = PaddingValues(horizontal = 30.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(when { document.text.isBlank() -> "No transcript is available for this recording."; document.words.isEmpty() -> "Word timing is unavailable for this recording."; approximateTiming -> "Approximate word times · Tap a word to listen from there."; else -> "Model word times · Tap a word to listen from there." },
                            modifier = Modifier.weight(1f), color = Muted, style = MaterialTheme.typography.bodySmall)
                        if (entry.hasAudio && document.words.isNotEmpty()) TextButton(onClick = { follow = !follow }) { Text(if (follow) "Following ✓" else "Follow audio") }
                    }
                }
                itemsIndexed(blocks, key = { _, block -> block.start }) { index, block ->
                    TranscriptCard(block, cue, index == activeBlock, names, canPlay, playFrom, document.speakers,
                        onAssign = { turnId, speakerId -> actions.assignTurnSpeaker(entry.id, turnId, speakerId) }, allowEdit = !state.capture.active && !state.speakerJob.active)
                }
            }
            1 -> LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("speaker-list"), contentPadding = PaddingValues(30.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item {
                    CardSection {
                        Text("A voice, then a name.", fontSize = 23.sp, fontWeight = FontWeight.SemiBold)
                        Text("Speakers are detected automatically. Add names you know, or correct a turn from the transcript. Names are your labels; voices do not reveal someone’s identity.", color = Muted)
                        Text(document.speakerStatus, color = Teal, style = MaterialTheme.typography.labelLarge)
                        if (!entry.hasAudio) Text("Speaker identification needs saved audio. This recording contains text only.", color = Muted, style = MaterialTheme.typography.bodySmall)
                        else if (state.speakerJob.active || state.speakerJob.installing) SpeakerProgress(state.speakerJob, actions)
                        else if (state.speakerJob.modelsInstalled) Button(onClick = { analyzeDialog = true }, enabled = canAnalyze) { Text(if (document.speakers.isEmpty()) "Identify speakers" else "Analyze speakers again") }
                        else OutlinedButton(onClick = onSettings) { Text("Set up speaker identification") }
                        Text("Runs after recording on this computer. Automatic count; overlapping or brief speech can need correction.", color = Muted, style = MaterialTheme.typography.bodySmall)
                    }
                }
                items(document.speakers, key = { it.id }) { speaker ->
                    CardSection { Row(verticalAlignment = Alignment.CenterVertically) {
                        SpeakerBadge(speaker.id, speaker.name)
                        Spacer(Modifier.weight(1f))
                        if (document.speakers.size > 1) TextButton(onClick = { mergeSource = speaker }, enabled = !state.speakerJob.active) { Text("Merge") }
                        TextButton(onClick = { onRenameSpeaker(document, speaker) }, enabled = !state.speakerJob.active) { Icon(Icons.Default.Edit, null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("Rename") }
                    } }
                }
            }
            else -> LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(30.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                item {
                    CardSection {
                        Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.AutoAwesome, null, tint = Teal); Spacer(Modifier.width(10.dp)); Text("The meeting, distilled.", fontSize = 22.sp, fontWeight = FontWeight.SemiBold) }
                        Text("Creating a summary sends this transcript to your selected AI provider. Your audio stays on this computer.", color = Muted, style = MaterialTheme.typography.bodySmall)
                        if (state.summaryBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                        Button(onClick = { actions.summarize(entry.id) }, enabled = document.text.isNotBlank() && state.apiKeySaved && !state.summaryBusy && !state.capture.active) {
                            Text(if (state.summaryBusy) "Creating summary…" else if (document.summary.isBlank()) "Create summary" else "Refresh summary")
                        }
                        if (document.text.isBlank()) Text("There are no transcribed words to summarize in this recording.", color = Muted, style = MaterialTheme.typography.bodySmall)
                        else if (!state.apiKeySaved) TextButton(onClick = onSettings) { Text("Add an AI key in Settings") }
                    }
                }
                if (document.summary.isNotBlank() || document.actionItems.isNotEmpty()) item {
                    CardSection {
                        Text("Summary", fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
                        SelectionContainer { Text(document.summary.ifBlank { "No summary text returned." }, style = MaterialTheme.typography.bodyLarge) }
                        if (document.actionItems.isNotEmpty()) {
                            HorizontalDivider(color = Line)
                            Text("Next steps", fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                            document.actionItems.forEachIndexed { index, item -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("${index + 1}.", color = Teal, fontWeight = FontWeight.Bold)
                                SelectionContainer { Text(item, style = MaterialTheme.typography.bodyLarge) }
                            } }
                        }
                        ActionRow(onCopy = { actions.copySummary(entry.id) }, onExport = { actions.exportSummary(entry.id) }, enabled = true)
                    }
                }
            }
        }
        if (entry.hasAudio) PlayerBar(position, duration, playing, player.speed, canPlay, actions)
    }
    if (analyzeDialog) AlertDialog(onDismissRequest = { analyzeDialog = false }, title = { Text("Identify speakers") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Let the app estimate the number of voices. If you know how many people spoke, supplying that number can help correct split labels.", color = Muted)
            ChoicePicker("Number of speakers", speakerCount, listOf("auto" to "Automatic detection") + (1..20).map { it.toString() to "$it ${if (it == 1) "speaker" else "speakers"}" },
                onSelect = { speakerCount = it })
            if (document.speakers.isNotEmpty() || document.turns.isNotEmpty()) Text("Running analysis again replaces existing speaker names and turn corrections. Your audio and words are preserved.", color = Error)
        }
    }, confirmButton = { TextButton(onClick = { analyzeDialog = false; actions.analyzeSpeakers(entry.id, speakerCount.toIntOrNull()) }, enabled = canAnalyze) { Text("Start analysis") } },
        dismissButton = { TextButton(onClick = { analyzeDialog = false }) { Text("Cancel") } })
    mergeSource?.let { source ->
        val targets = document.speakers.filter { it.id != source.id }
        var targetId by remember(source.id) { mutableStateOf(targets.firstOrNull()?.id.orEmpty()) }
        AlertDialog(onDismissRequest = { mergeSource = null }, title = { Text("Merge speaker labels") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Use this when one person was detected as two speakers. All turns labeled “${source.name}” will move to the selected speaker, whose name will be kept.", color = Muted)
                ChoicePicker("Merge ${source.name} into", targetId, targets.map { it.id to it.name }, onSelect = { targetId = it })
            }
        }, confirmButton = { TextButton(onClick = { actions.mergeSpeakers(entry.id, source.id, targetId); mergeSource = null }, enabled = targets.any { it.id == targetId } && !state.speakerJob.active) { Text("Merge speakers") } },
            dismissButton = { TextButton(onClick = { mergeSource = null }) { Text("Cancel") } })
    }
}

@Composable
private fun TranscriptCard(block: TranscriptBlock, activeWord: WordCue?, active: Boolean, names: Map<String, String>, canPlay: Boolean,
    onPlayFrom: (Long) -> Unit, speakers: List<SpeakerName>, onAssign: (Int, String?) -> Unit, allowEdit: Boolean) {
    var menu by remember(block.start) { mutableStateOf(false) }
    Surface(color = if (active) Color.White else Paper, shape = RoundedCornerShape(16.dp), border = if (active) androidx.compose.foundation.BorderStroke(1.dp, Line) else null) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val turn = block.turn
                if (turn != null) {
                    Box {
                        Row(Modifier.clip(RoundedCornerShape(12.dp)).clickable(enabled = allowEdit && speakers.isNotEmpty()) { menu = true }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            SpeakerBadge(turn.speakerId, if (turn.overlapping) "Overlapping voices" else names[turn.speakerId] ?: "Unknown speaker")
                            if (allowEdit && speakers.isNotEmpty()) Icon(Icons.Default.ArrowDropDown, "Correct speaker", tint = Muted, modifier = Modifier.size(17.dp))
                        }
                        DropdownMenu(menu, { menu = false }) {
                            speakers.forEach { speaker -> DropdownMenuItem(text = { Text(speaker.name) }, onClick = { menu = false; onAssign(turn.id, speaker.id) }) }
                            DropdownMenuItem(text = { Text("Unknown speaker") }, onClick = { menu = false; onAssign(turn.id, null) })
                        }
                    }
                }
                val first = block.words.firstOrNull()
                val time = first?.startMs ?: turn?.startMs
                if (time != null) TextButton(onClick = { onPlayFrom(time) }, enabled = canPlay, contentPadding = PaddingValues(horizontal = 5.dp, vertical = 0.dp), modifier = Modifier.height(28.dp)) {
                    Text((if (first?.isEstimated == true) "≈ " else "") + durationLabel(time), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall)
                }
            }
            if (block.words.isEmpty() || !canPlay) SelectionContainer { Text(block.text, style = MaterialTheme.typography.bodyLarge) }
            else {
                val styled = remember(block, activeWord) {
                    buildAnnotatedString {
                        append(block.text)
                        block.words.forEach { word ->
                            val start = (word.startChar - block.start).coerceIn(0, length)
                            val end = (word.endChar - block.start).coerceIn(start, length)
                            if (end > start) {
                                addStringAnnotation("seek", word.startMs.toString(), start, end)
                                if (word == activeWord) addStyle(SpanStyle(background = Accent, color = Ink, fontWeight = FontWeight.SemiBold), start, end)
                            }
                        }
                    }
                }
                ClickableText(styled, style = MaterialTheme.typography.bodyLarge.copy(color = Ink), modifier = Modifier.fillMaxWidth(),
                    onClick = { offset -> styled.getStringAnnotations("seek", offset, offset).firstOrNull()?.item?.toLongOrNull()?.let(onPlayFrom) })
            }
        }
    }
}

@Composable
private fun PlayerBar(position: Long, duration: Long, playing: Boolean, speed: Float, enabled: Boolean, actions: DesktopActions) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    Surface(color = Color.White, shadowElevation = 8.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 30.dp, vertical = 12.dp)) {
            Slider(value = dragging ?: position.toFloat().coerceIn(0f, duration.coerceAtLeast(1).toFloat()),
                onValueChange = { dragging = it }, onValueChangeFinished = { dragging?.let { actions.seekTo(it.toLong()) }; dragging = null },
                valueRange = 0f..duration.coerceAtLeast(1).toFloat(), enabled = enabled && duration > 0,
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Playback position" })
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("${durationLabel(dragging?.toLong() ?: position)} / ${durationLabel(duration)}", fontFamily = FontFamily.Monospace, color = Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                IconButton(onClick = { actions.seekTo((position - 10_000).coerceAtLeast(0)) }, enabled = enabled) { Icon(Icons.Default.Replay10, "Back 10 seconds") }
                FilledIconButton(onClick = actions::playPause, enabled = enabled, modifier = Modifier.size(48.dp)) { Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (playing) "Pause" else "Play") }
                IconButton(onClick = { actions.seekTo((position + 10_000).coerceAtMost(duration)) }, enabled = enabled) { Icon(Icons.Default.Forward10, "Forward 10 seconds") }
                ChoicePicker("Speed", speed.toString(), listOf(.5f, .75f, 1f, 1.25f, 1.5f, 2f).map { it.toString() to "${it}×" },
                    enabled = enabled, modifier = Modifier.width(105.dp), onSelect = { actions.setPlaybackSpeed(it.toFloat()) })
            }
            Text(if (enabled) "Speed changes also change voice pitch." else "Finish recording or speaker processing to play audio. Speed changes voice pitch.", color = Muted,
                style = MaterialTheme.typography.labelSmall, modifier = Modifier.fillMaxWidth().padding(top = 3.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingsPage(state: AppState, actions: DesktopActions, onRemove: (SpeechModel) -> Unit, onDeleteKey: () -> Unit) {
    val settings = state.settings
    val busy = state.capture.active || state.speakerJob.active || state.speakerJob.installing || state.audioCheck.active
    val canEditAi = !state.capture.active && !state.summaryBusy && !state.connectionBusy && !state.audioCheck.active
    var key by remember { mutableStateOf("") }
    var summaryModel by remember(settings.providerId, settings.summaryModel) { mutableStateOf(settings.summaryModel) }
    val provider = LlmProvider.entries.firstOrNull { it.name == settings.providerId } ?: LlmProvider.OPENAI
    val selectedModel = SpeechModel.fromId(settings.modelId) ?: SpeechModel.NEMOTRON_ENGLISH
    LazyColumn(Modifier.fillMaxSize().testTag("settings-page"), contentPadding = PaddingValues(32.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        item { PageTitle("Make it work your way", "A thoughtful setup", "Choose local speech models, manage your microphone, and connect optional AI summaries.") }
        item { AudioSettings(state, actions) }
        item {
            CardSection {
                SectionHeading(Icons.Default.GraphicEq, "Local transcription")
                Text("Speech models run on this computer. Download a model from GitHub or import its file, then select it for your next recording.", color = Muted)
                TextButton(onClick = actions::openModelDownloads) { Text("Open model downloads on GitHub") }
                Text("If the app cannot download on your network, download the matching .gguf file in your browser, then choose Import .gguf on its model card below.", color = Muted, style = MaterialTheme.typography.bodySmall)
                ChoicePicker("Language for ${selectedModel.title}", settings.languageCode, selectedModel.languages.map { it.code to it.label }, enabled = !busy,
                    onSelect = { actions.updateSettings(settings.copy(languageCode = it)) })
                Text("Nemotron English is the starting choice for English meetings. Moonshine Tiny has a 4,096-token output limit and is suited to shorter notes.", color = Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
        items(SpeechModel.entries.toList(), key = { it.id }) { model ->
            val download = state.downloads.firstOrNull { it.modelId == model.id } ?: DownloadView(model.id)
            val selected = settings.modelId == model.id
            CardSection {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(model.title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        Text("${model.approxSizeMb} MB · ${if (model.multilingual) "Multilingual" else "English"}", color = Muted, style = MaterialTheme.typography.bodySmall)
                    }
                    if (selected) Pill("SELECTED", Mint, Teal) else if (download.installed) Pill("DOWNLOADED", Wash, Muted)
                }
                Text(model.description.replace("your phone", "your computer").replace("phone accuracy", "desktop accuracy"), color = Muted, style = MaterialTheme.typography.bodyMedium)
                SelectionContainer { Text(model.fileName, color = Muted, fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
                if (download.downloading) {
                    Progress(download.fraction)
                    Text(download.message.ifBlank { "Preparing model…" }, color = Muted, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { actions.cancelModelDownload(model.id) }, modifier = Modifier.testTag("cancel-model-${model.id}")) { Text("Cancel") }
                } else FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (download.installed) {
                        Button(onClick = { actions.updateSettings(settings.copy(modelId = model.id, languageCode = if (model.languages.any { it.code == settings.languageCode }) settings.languageCode else "en-US")) }, enabled = !busy && !selected) { Text(if (selected) "Selected" else "Use model") }
                        TextButton(onClick = { onRemove(model) }, enabled = !busy) { Text("Remove download") }
                    } else OutlinedButton(onClick = { actions.downloadModel(model.id) }, enabled = !busy && state.downloads.none { it.downloading }, modifier = Modifier.testTag("download-model-${model.id}")) {
                        Icon(Icons.Default.Download, null, modifier = Modifier.size(17.dp)); Spacer(Modifier.width(7.dp)); Text("Download from GitHub")
                    }
                    OutlinedButton(onClick = { actions.importModel(model.id) }, enabled = !busy && state.downloads.none { it.downloading }, modifier = Modifier.testTag("import-model-${model.id}")) {
                        Icon(Icons.Default.FolderOpen, null, modifier = Modifier.size(17.dp)); Spacer(Modifier.width(7.dp)); Text("Import .gguf")
                    }
                }
                if (!download.downloading && download.message.isNotBlank()) Text(download.message, color = Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
        item {
            CardSection {
                SectionHeading(Icons.Default.People, "Speaker identification")
                Text("Find the different voices after a meeting, then give each speaker a name. The number of speakers is detected automatically.", color = Muted)
                Text("Separate local model download · approximately 47 MB", color = Teal, style = MaterialTheme.typography.labelLarge)
                if (state.speakerJob.installing || state.speakerJob.active) SpeakerProgress(state.speakerJob, actions)
                else if (!state.speakerJob.modelsInstalled) OutlinedButton(onClick = actions::installSpeakerModels, enabled = !state.capture.active && state.downloads.none { it.downloading }) { Text("Download speaker models") }
                else Pill("SPEAKER MODELS READY", Mint, Teal)
                ToggleRow("Identify speakers after recording", "Runs after speech transcription finishes, so the two models do not compete during capture.", settings.autoSpeakers, enabled = !busy) { actions.updateSettings(settings.copy(autoSpeakers = it)) }
                Text("Labels are best-effort. Brief replies, similar voices, and overlapping speech can need manual correction. Speaker names are entered by you.", color = Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
        item {
            CardSection {
                SectionHeading(Icons.Default.AutoAwesome, "Optional AI summaries")
                Text("Summaries send transcript text to your selected provider using your API key. The provider may charge for usage. Audio recording and local transcription do not need a key.", color = Muted)
                if (state.capture.active) Text("Finish recording before changing AI settings.", color = Teal, style = MaterialTheme.typography.labelLarge)
                ChoicePicker("AI provider", provider.name, LlmProvider.entries.map { it.name to it.displayName }, enabled = canEditAi,
                    onSelect = { id -> key = ""; actions.updateSettings(settings.copy(providerId = id, summaryModel = "", autoSummaries = false)) })
                OutlinedTextField(summaryModel, { summaryModel = it }, label = { Text("Summary model") }, placeholder = { Text(provider.defaultModel) }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = canEditAi)
                OutlinedTextField(key, { key = it }, label = { Text(if (state.apiKeySaved) "Replace saved API key" else "API key") },
                    visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth().testTag("api-key-field"), enabled = canEditAi)
                Text(if (state.apiKeySaved) "A key is saved locally. Leave the field blank to keep it." else "No key saved. Enter a key only if you want cloud summaries.", color = Muted, style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { actions.updateSettings(settings.copy(summaryModel = summaryModel.trim())); if (key.isNotBlank()) actions.saveApiKey(key.trim()); key = "" }, enabled = canEditAi) { Text("Save AI settings") }
                    OutlinedButton(onClick = actions::testConnection, enabled = state.apiKeySaved && canEditAi) { Text(if (state.connectionBusy) "Testing…" else "Test saved connection") }
                    if (state.apiKeySaved) TextButton(onClick = onDeleteKey, enabled = canEditAi) { Text("Remove key") }
                }
                if (state.connectionBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                ToggleRow("Automatically create summaries", "Opt in to send transcript text to ${provider.displayName} as your meeting progresses.", settings.autoSummaries,
                    enabled = state.apiKeySaved && canEditAi) { actions.updateSettings(settings.copy(autoSummaries = it)) }
            }
        }
        item { Text("Models and notes are stored separately. Removing a model keeps your recordings. Save an audio or transcript file from the library to share it outside this app.", color = Muted, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun SpeakerProgress(job: SpeakerJobView, actions: DesktopActions) {
    Column(Modifier.fillMaxWidth().background(Wash, RoundedCornerShape(16.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (job.installing) "Setting up speaker models" else "Finding the voices", modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
            TextButton(onClick = actions::cancelSpeakerJob) { Text("Cancel") }
        }
        Progress(job.fraction)
        Text(job.message.ifBlank { "Processing on this computer. Your saved audio and transcript remain available." }, color = Muted, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun Progress(fraction: Float?) {
    if (fraction == null || !fraction.isFinite()) LinearProgressIndicator(Modifier.fillMaxWidth())
    else LinearProgressIndicator(progress = fraction.coerceIn(0f, 1f), modifier = Modifier.fillMaxWidth())
}

@Composable
private fun ChoicePicker(label: String, selected: String, choices: List<Pair<String, String>>, enabled: Boolean = true,
    modifier: Modifier = Modifier.fillMaxWidth(), onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(onClick = { expanded = true }, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 46.dp), shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 9.dp)) {
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                Text(label, color = Muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(choices.firstOrNull { it.first == selected }?.second ?: selected.ifBlank { "Select" }, color = if (enabled) Ink else Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.Default.ArrowDropDown, null, modifier = Modifier.size(20.dp))
        }
        DropdownMenu(expanded && enabled, { expanded = false }, modifier = Modifier.heightIn(max = 380.dp)) {
            choices.forEach { (id, title) -> DropdownMenuItem(text = { Text(title) }, onClick = { expanded = false; onSelect(id) }, trailingIcon = if (id == selected) ({ Icon(Icons.Default.Check, null, tint = Teal, modifier = Modifier.size(18.dp)) }) else null) }
        }
    }
}

@Composable
private fun ToggleRow(title: String, description: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) { Text(title, fontWeight = FontWeight.SemiBold); Text(description, color = Muted, style = MaterialTheme.typography.bodySmall) }
        Switch(checked, onCheckedChange = onChange, enabled = enabled, modifier = Modifier.semantics { contentDescription = title })
    }
}

@Composable
private fun SectionHeading(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) { Icon(icon, null, tint = Teal, modifier = Modifier.size(23.dp)); Text(text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold) }
}

@Composable
private fun Pill(label: String, background: Color, color: Color) {
    Text(label, Modifier.background(background, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 5.dp), color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = .7.sp)
}

@Composable
private fun SpeakerBadge(id: String?, name: String) {
    val color = if (id == null) Muted else SpeakerColors[(id.hashCode() and Int.MAX_VALUE) % SpeakerColors.size]
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        Box(Modifier.size(7.dp).background(color, CircleShape)); Text(name, color = color, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 220.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActionRow(onCopy: () -> Unit, onExport: () -> Unit, enabled: Boolean, extra: @Composable RowScope.() -> Unit = {}) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = onCopy, enabled = enabled, shape = RoundedCornerShape(12.dp)) { Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(7.dp)); Text("Copy text") }
        OutlinedButton(onClick = onExport, enabled = enabled, shape = RoundedCornerShape(12.dp)) { Icon(Icons.Default.FileDownload, null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(7.dp)); Text("Save .txt") }
        extra()
    }
}

@Composable
private fun FullTranscriptDialog(text: String, captureActive: Boolean, actions: DesktopActions) {
    val blocks = remember(text) { transcriptBlocks(text) }
    AlertDialog(onDismissRequest = actions::closeFullTranscript, modifier = Modifier.width(760.dp), title = { Text("Full transcript") }, text = {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(if (captureActive) "Recording continues. Refresh this snapshot to see newer words. Copy and save include the newest full transcript." else "All available words from your most recent capture.", color = Muted, style = MaterialTheme.typography.bodySmall)
            LazyColumn(Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 280.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (blocks.isEmpty()) item { Text("No words have been transcribed yet.") }
                items(blocks, key = { it.start }) { block -> SelectionContainer { Text(block.text, style = MaterialTheme.typography.bodyLarge) } }
            }
            ActionRow({ actions.copyTranscript(null) }, { actions.exportTranscript(null) }, text.isNotBlank())
        }
    }, confirmButton = { TextButton(onClick = actions::openFullTranscript) { Text("Refresh transcript") } },
        dismissButton = { TextButton(onClick = actions::closeFullTranscript) { Text("Close") } })
}

private fun dateLabel(milliseconds: Long): String = Instant.ofEpochMilli(milliseconds).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("EEE, d MMM · HH:mm", Locale.getDefault()))
