package com.sainadh.livenotes

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sainadh.livenotes.ai.LlmProvider
import com.sainadh.livenotes.audio.AudioInputMode
import com.sainadh.livenotes.data.DailyNote
import com.sainadh.livenotes.data.SavedRecording
import com.sainadh.livenotes.sharing.RecordingSharing
import com.sainadh.livenotes.ui.RecorderHero
import com.sainadh.livenotes.ui.LiveTranscriptPanel
import com.sainadh.livenotes.ui.RecordingLibraryCard
import com.sainadh.livenotes.ui.RecordingDetailScreen
import com.sainadh.livenotes.ui.TextActions
import com.sainadh.livenotes.ui.recordingTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.sainadh.livenotes.service.CapturePhase
import com.sainadh.livenotes.service.ServiceStateTracker
import com.sainadh.livenotes.stt.ModelDownloadState
import com.sainadh.livenotes.stt.SpeechLanguage
import com.sainadh.livenotes.stt.SpeechModel
import java.time.LocalDate
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.io.File

private val Paper = Color(0xFFF6F5F0)
private val Ink = Color(0xFF203632)
private val Teal = Color(0xFF17695B)
private val SoftTeal = Color(0xFFE5EEE8)
private val Muted = Color(0xFF60716B)
private val Line = Color(0xFFDAE1DA)
private val ErrorInk = Color(0xFF983B32)
private val ErrorPaper = Color(0xFFFFEEE8)

class MainActivity : ComponentActivity() {
    companion object {
        const val EXTRA_AUTO_TEST_OPENAI = "extra_auto_test_openai"
    }

    private val viewModel by viewModels<MainViewModel> {
        MainViewModel.Factory(application as LiveNotesApplication)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        )
        val activityLifecycle = lifecycle
        setContent {
            LiveNotesTheme { LiveNotesScreen(viewModel, activityLifecycle) }
        }
        if (intent.getBooleanExtra(EXTRA_AUTO_TEST_OPENAI, false)) {
            viewModel.testConnection(viewModel.currentProvider(), viewModel.currentModel(), "")
        }
    }

    override fun onStop() {
        viewModel.pausePlayback()
        super.onStop()
    }
}

@Composable
internal fun LiveNotesTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Teal, onPrimary = Color.White,
            primaryContainer = SoftTeal, onPrimaryContainer = Ink,
            secondary = Teal, onSecondary = Color.White,
            secondaryContainer = SoftTeal, onSecondaryContainer = Ink,
            background = Paper, onBackground = Ink,
            surface = Color.White, onSurface = Ink,
            surfaceVariant = SoftTeal, onSurfaceVariant = Muted,
            outline = Muted, outlineVariant = Line,
            error = ErrorInk, errorContainer = ErrorPaper, onErrorContainer = ErrorInk
        ),
        typography = Typography(
            headlineLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 32.sp, lineHeight = 38.sp, letterSpacing = (-0.8).sp),
            headlineSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 25.sp, lineHeight = 32.sp, letterSpacing = (-0.5).sp),
            titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 21.sp, lineHeight = 28.sp),
            titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 24.sp),
            bodyLarge = TextStyle(fontSize = 17.sp, lineHeight = 27.sp),
            bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 23.sp),
            bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 19.sp),
            labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp),
            labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 17.sp)
        ),
        content = content
    )
}

private enum class AppScreen(val title: String, val icon: NoteIcon) {
    RECORD("Record", NoteIcon.MIC), NOTES("Notes", NoteIcon.NOTES), SETTINGS("Settings", NoteIcon.SETTINGS)
}

@Composable
private fun LiveNotesScreen(viewModel: MainViewModel, activityLifecycle: Lifecycle) {
    val context = LocalContext.current
    var screen by rememberSaveable { mutableStateOf(AppScreen.RECORD) }
    val recordScroll = rememberLazyListState()
    val notesScroll = rememberLazyListState()
    val settingsScroll = rememberLazyListState()
    val todayNote by viewModel.todayNote.collectAsStateWithLifecycle(lifecycle = activityLifecycle)
    val allNotes by viewModel.allNotes.collectAsStateWithLifecycle(lifecycle = activityLifecycle)
    val savedRecordings by viewModel.savedRecordings.collectAsStateWithLifecycle(lifecycle = activityLifecycle)
    val playback by viewModel.playback.collectAsStateWithLifecycle(lifecycle = activityLifecycle)
    val durationMs by ServiceStateTracker.durationMs.collectAsStateWithLifecycle(lifecycle = activityLifecycle)
    val audioLevel by ServiceStateTracker.audioLevel.collectAsStateWithLifecycle(lifecycle = activityLifecycle)
    val liveSegments by ServiceStateTracker.liveSegments.collectAsStateWithLifecycle(lifecycle = activityLifecycle)
    val audioNotice by ServiceStateTracker.audioNotice.collectAsStateWithLifecycle(lifecycle = activityLifecycle)
    var openedRecordingId by rememberSaveable { mutableStateOf<String?>(null) }
    // Only a short cache filename enters saved state; a long meeting must not overflow its Bundle.
    var exportDraftName by rememberSaveable { mutableStateOf<String?>(null) }
    val coroutineScope = rememberCoroutineScope()
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val draftName = exportDraftName
        exportDraftName = null
        if (draftName != null) {
            coroutineScope.launch {
                val result = withContext(Dispatchers.IO) {
                    val draft = File(context.cacheDir, draftName)
                    runCatching {
                        if (uri != null) {
                            requireNotNull(context.contentResolver.openOutputStream(uri, "wt")) { "Unable to open the selected file" }
                                .use { output -> draft.inputStream().use { it.copyTo(output) } }
                        }
                    }.also { draft.delete() }
                }
                if (uri != null) Toast.makeText(context, if (result.isSuccess) "Text file saved" else "Could not save this file. Please try another location.", Toast.LENGTH_LONG).show()
            }
        }
    }
    fun export(text: String, filename: String) {
        coroutineScope.launch {
            val draft = withContext(Dispatchers.IO) {
                runCatching { File.createTempFile("text-export-", ".txt", context.cacheDir).apply { writeText(text, Charsets.UTF_8) } }
            }
            draft.onSuccess { file ->
                exportDraftName = file.name
                runCatching { exportLauncher.launch(filename) }.onFailure {
                    exportDraftName = null
                    withContext(Dispatchers.IO) { file.delete() }
                    Toast.makeText(context, "A document app is needed to save a text file.", Toast.LENGTH_LONG).show()
                }
            }.onFailure {
                Toast.makeText(context, "Could not prepare this text file. Check device storage.", Toast.LENGTH_LONG).show()
            }
        }
    }
    val latestTranscript by viewModel.latestTranscript.collectAsStateWithLifecycle(lifecycle = activityLifecycle)
    val connectionStatus by viewModel.connectionStatus.collectAsStateWithLifecycle(lifecycle = activityLifecycle)
    val currentAudioRoute by ServiceStateTracker.audioRoute.collectAsStateWithLifecycle(lifecycle = activityLifecycle)
    val capturePhase by ServiceStateTracker.capturePhase.collectAsStateWithLifecycle(lifecycle = activityLifecycle)
    val activeEngine by ServiceStateTracker.activeEngine.collectAsStateWithLifecycle(lifecycle = activityLifecycle)
    val summaryError by viewModel.summaryError.collectAsStateWithLifecycle(lifecycle = activityLifecycle)
    val transcriptionError by ServiceStateTracker.lastTranscriptionError.collectAsStateWithLifecycle(lifecycle = activityLifecycle)
    val downloadState by viewModel.modelDownloadState.collectAsStateWithLifecycle(lifecycle = activityLifecycle)
    val downloadTarget by viewModel.modelDownloadTarget.collectAsStateWithLifecycle(lifecycle = activityLifecycle)
    val downloadedModels by viewModel.downloadedModels.collectAsStateWithLifecycle(lifecycle = activityLifecycle)
    val speechSettings by viewModel.speechSettings.collectAsStateWithLifecycle(lifecycle = activityLifecycle)
    // A newly entered secret is deliberately excluded from saved instance state.
    var apiKey by remember { mutableStateOf("") }
    var selectedProvider by rememberSaveable { mutableStateOf(viewModel.currentProvider()) }
    var selectedModel by rememberSaveable { mutableStateOf(viewModel.currentModel()) }
    var selectedAudioInput by rememberSaveable { mutableStateOf(viewModel.currentAudioInputMode()) }
    var savedAudioInput by rememberSaveable { mutableStateOf(viewModel.currentAudioInputMode()) }
    var showMoreModels by rememberSaveable { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        val microphoneGranted = granted[Manifest.permission.RECORD_AUDIO]
            ?: (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
        if (microphoneGranted) viewModel.startListening()
        else ServiceStateTracker.lastTranscriptionError.value =
            "Allow microphone access in your phone's app settings, then try recording again."
    }
    fun startRecording() {
        val permissions = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && viewModel.currentAudioInputMode() != AudioInputMode.PHONE_MIC) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }.toTypedArray()
        if (permissions.isEmpty()) viewModel.startListening() else permissionLauncher.launch(permissions)
    }

    val openedRecording = savedRecordings.firstOrNull { it.recordingId == openedRecordingId }
    if (openedRecording != null) {
        BackHandler { openedRecordingId = null }
        Box(Modifier.fillMaxSize().background(Paper).safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
            Box(Modifier.widthIn(max = 840.dp).fillMaxSize()) {
                RecordingDetailScreen(
                    recording = openedRecording,
                    playback = playback,
                    captureActive = capturePhase != CapturePhase.IDLE,
                    onBack = { openedRecordingId = null },
                    onPlay = {
                        if (playback.recordingId == openedRecording.recordingId) viewModel.togglePlayback()
                        else viewModel.playRecording(openedRecording)
                    },
                    onPlayFrom = { position -> viewModel.playRecording(openedRecording, position) },
                    onSeek = { position ->
                        if (playback.recordingId == openedRecording.recordingId) viewModel.seekPlayback(position)
                        else viewModel.playRecording(openedRecording, position)
                    },
                    onSpeed = viewModel::setPlaybackSpeed,
                    onCopy = { RecordingSharing.copyText(context, openedRecording.text, "Transcript") },
                    onShare = { RecordingSharing.shareText(context, openedRecording.text, openedRecording.title) },
                    onShareAudio = { RecordingSharing.shareAudio(context, openedRecording) },
                    onExport = { export(openedRecording.text, "transcript-${openedRecording.dateKey}.txt") }
                )
            }
        }
        return
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = Paper,
        bottomBar = {
            Column {
                val currentRecording = savedRecordings.firstOrNull { it.recordingId == playback.recordingId }
                if (currentRecording != null && capturePhase == CapturePhase.IDLE) {
                    Surface(color = SoftTeal, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { openedRecordingId = currentRecording.recordingId }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                                Column(Modifier.fillMaxWidth()) {
                                    Text(currentRecording.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("${recordingTime(playback.positionMs)} / ${recordingTime(playback.durationMs)} · Open player", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            TextButton(onClick = viewModel::togglePlayback, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (playback.isPlaying) "Pause" else "Play") }
                        }
                    }
                }
                NavigationBar(containerColor = Paper, tonalElevation = 0.dp) {
                AppScreen.entries.forEach { destination ->
                    NavigationBarItem(
                        selected = screen == destination,
                        onClick = { screen = destination },
                        icon = { LineIcon(destination.icon, if (screen == destination) Teal else Muted) },
                        label = { Text(destination.title) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Teal, selectedTextColor = Ink,
                            indicatorColor = SoftTeal, unselectedIconColor = Muted, unselectedTextColor = Muted
                        )
                    )
                }
                }
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier.widthIn(max = 840.dp).fillMaxSize().imePadding(),
            state = when (screen) {
                AppScreen.RECORD -> recordScroll
                AppScreen.NOTES -> notesScroll
                AppScreen.SETTINGS -> settingsScroll
            },
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            item { BrandHeader() }
            when (screen) {
                AppScreen.RECORD -> {
                    item {
                        PageHeading(
                            title = "Listen. Capture. Remember.",
                            subtitle = "Your conversations, ready to replay."
                        )
                    }
                    item {
                        RecorderHero(
                            phase = capturePhase,
                            durationMs = durationMs,
                            audioLevel = audioLevel,
                            activeEngine = activeEngine,
                            selectedEngine = speechSettings.model?.title ?: "Android speech · Transcript only",
                            audioRoute = currentAudioRoute,
                            onToggle = {
                                if (capturePhase == CapturePhase.PREPARING || capturePhase == CapturePhase.RECORDING) viewModel.stopListening()
                                else if (capturePhase == CapturePhase.IDLE) startRecording()
                            }
                        )
                    }
                    if (speechSettings.model == null && capturePhase == CapturePhase.IDLE) {
                        item {
                            NoteSurface {
                                Text("Keep the audio, too", style = MaterialTheme.typography.titleMedium)
                                Text("Android speech saves text only. Select an on-device speech model to save audio and replay it with your transcript.", style = MaterialTheme.typography.bodyMedium, color = Muted)
                                TextButton(onClick = { screen = AppScreen.SETTINGS }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Choose a recording model") }
                            }
                        }
                    }
                    if (!transcriptionError.isNullOrBlank()) {
                        item { AttentionCard("Recording needs attention", transcriptionError!!) }
                    }
                    if (!audioNotice.isNullOrBlank()) {
                        item { AttentionCard("Audio recording", audioNotice!!) }
                    }
                    item {
                        LiveTranscriptPanel(capturePhase, latestTranscript, liveSegments,
                            onCopy = { RecordingSharing.copyText(context, latestTranscript, "Transcript") },
                            onShare = { RecordingSharing.shareText(context, latestTranscript, "Live transcript") },
                            onExport = { export(latestTranscript, "transcript-${LocalDate.now()}.txt") })
                    }
                    if (capturePhase == CapturePhase.IDLE && savedRecordings.isNotEmpty()) {
                        item {
                            TextButton(onClick = { openedRecordingId = savedRecordings.first().recordingId }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                                Text("Open latest recording & transcript →")
                            }
                        }
                    }
                    item {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Today's notes", style = MaterialTheme.typography.titleLarge)
                            TextButton(onClick = { screen = AppScreen.NOTES }, modifier = Modifier.heightIn(min = 48.dp)) { Text("View all") }
                        }
                    }
                    item { SummaryCard(todayNote, compact = true, onExport = { text -> export(text, "notes-${LocalDate.now()}.txt") }) }
                    if (!summaryError.isNullOrBlank()) {
                        item { AttentionCard("Summary needs attention", summaryError!!, "Retry summary", viewModel::retrySummary) }
                    }
                }
                AppScreen.NOTES -> {
                    item { PageHeading("Your library", "Replay the conversation. Rediscover the details.") }
                    if (!summaryError.isNullOrBlank()) {
                        item { AttentionCard("Summary needs attention", summaryError!!, "Retry summary", viewModel::retrySummary) }
                    }
                    if (allNotes.isEmpty() && savedRecordings.isEmpty()) {
                        item {
                            EmptyState(
                                title = "Your notebook starts here",
                                body = "Record a conversation to keep your transcript here. AI summaries are optional and can be connected in Settings.",
                                icon = NoteIcon.NOTES
                            )
                        }
                    } else {
                        if (savedRecordings.isNotEmpty()) {
                            item { Text("Recordings · ${savedRecordings.size}", style = MaterialTheme.typography.titleLarge) }
                            items(savedRecordings, key = { "recording:${it.recordingId}" }) { recording ->
                                RecordingLibraryCard(recording,
                                    isPlaying = playback.recordingId == recording.recordingId && playback.isPlaying,
                                    playbackEnabled = capturePhase == CapturePhase.IDLE,
                                    onOpen = { openedRecordingId = recording.recordingId },
                                    onPlay = {
                                        openedRecordingId = recording.recordingId
                                        if (playback.recordingId == recording.recordingId) viewModel.togglePlayback()
                                        else viewModel.playRecording(recording)
                                    })
                            }
                        }
                        if (allNotes.isNotEmpty()) {
                            item { Text("Daily summaries", style = MaterialTheme.typography.titleLarge) }
                        }
                        items(allNotes, key = { "note:${it.dateKey}" }) { note -> HistoryCard(note, onExport = { text -> export(text, "notes-${note.dateKey}.txt") }) }
                    }
                }
                AppScreen.SETTINGS -> {
                    item { PageHeading("Make it yours", "Choose how you listen and how you take notes.") }
                    item {
                        SpeechSettingsPanel(
                            capturePhase = capturePhase,
                            activeEngine = activeEngine,
                            selectedModel = speechSettings.model,
                            selectedLanguage = speechSettings.language,
                            downloadedModels = downloadedModels,
                            downloadState = downloadState,
                            downloadTarget = downloadTarget,
                            showMoreModels = showMoreModels,
                            onToggleMoreModels = { showMoreModels = !showMoreModels },
                            onSelectModel = viewModel::selectSpeechModel,
                            onSelectLanguage = viewModel::selectSpeechLanguage,
                            onDownload = viewModel::downloadModel,
                            onRemove = viewModel::deleteModel
                        )
                    }
                    item {
                        MicrophoneSettingsPanel(
                            selected = selectedAudioInput,
                            saved = savedAudioInput,
                            onSelect = { selectedAudioInput = it },
                            onSave = {
                                viewModel.saveAudioInputMode(selectedAudioInput)
                                savedAudioInput = selectedAudioInput
                            }
                        )
                    }
                    item {
                        AiSettingsPanel(
                            provider = selectedProvider,
                            model = selectedModel,
                            apiKey = apiKey,
                            connectionStatus = connectionStatus,
                            onProviderChange = { provider ->
                                selectedProvider = provider
                                selectedModel = provider.defaultModel
                            },
                            onModelChange = { selectedModel = it },
                            onApiKeyChange = { apiKey = it },
                            onSave = {
                                viewModel.saveSettings(selectedProvider, selectedModel, apiKey, savedAudioInput)
                                apiKey = ""
                            },
                            onTestConnection = { viewModel.testConnection(selectedProvider, selectedModel, apiKey) }
                        )
                    }
                    item { Text("Live Notes  ·  ${BuildConfig.VERSION_NAME}", modifier = Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall, color = Muted) }
                }
            }
        }
    }
    }
}

@Composable
private fun SpeechSettingsPanel(
    capturePhase: CapturePhase,
    activeEngine: String,
    selectedModel: SpeechModel?,
    selectedLanguage: SpeechLanguage,
    downloadedModels: Set<SpeechModel>,
    downloadState: ModelDownloadState,
    downloadTarget: SpeechModel?,
    showMoreModels: Boolean,
    onToggleMoreModels: () -> Unit,
    onSelectModel: (SpeechModel?) -> Unit,
    onSelectLanguage: (SpeechLanguage) -> Unit,
    onDownload: (SpeechModel) -> Unit,
    onRemove: (SpeechModel) -> Unit
) {
    SettingsSection("Speech recognition", "Your choice applies to the next recording.", NoteIcon.WAVE) {
        if (capturePhase != CapturePhase.IDLE) {
            Text("Current recording: $activeEngine", style = MaterialTheme.typography.bodySmall, color = Teal)
        }
        SelectionRow(
            title = "Android speech",
            description = "Transcript only; audio is not saved. Internet use depends on your device. Choose an on-device model below for audio playback.",
            selected = selectedModel == null,
            onClick = { onSelectModel(null) }
        )
        Text("Language", style = MaterialTheme.typography.titleMedium)
        val languages = selectedModel?.languages ?: listOf(SpeechLanguage.ENGLISH)
        LanguageSelector(selectedLanguage, languages, onSelectLanguage)
        SectionDivider()
        Text("On-device models", style = MaterialTheme.typography.titleMedium)
        Text("Download a model, then choose Use this model. Downloads stay on your phone.", style = MaterialTheme.typography.bodySmall, color = Muted)
        SpeechModel.entries.filter { model ->
            showMoreModels || model.recommendation != null || model in downloadedModels ||
                model == selectedModel || model == downloadTarget
        }.forEach { model ->
            SpeechModelOption(
                model = model,
                selected = selectedModel == model,
                installed = model in downloadedModels,
                downloadState = downloadState,
                isDownloadTarget = downloadTarget == model,
                canRemove = capturePhase == CapturePhase.IDLE,
                onDownload = { onDownload(model) },
                onUse = { onSelectModel(model) },
                onRemove = { onRemove(model) }
            )
        }
        TextButton(onClick = onToggleMoreModels, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(if (showMoreModels) "Show fewer models" else "More model variants")
        }
    }
}

@Composable
private fun MicrophoneSettingsPanel(
    selected: AudioInputMode,
    saved: AudioInputMode,
    onSelect: (AudioInputMode) -> Unit,
    onSave: () -> Unit
) {
    SettingsSection("Microphone", "Choose the source for your next recording.", NoteIcon.MIC) {
        AudioInputMode.entries.forEach { mode ->
            SelectionRow(
                title = mode.displayName,
                description = if (mode == AudioInputMode.AUTO) "Bluetooth when available, otherwise your phone." else null,
                selected = selected == mode,
                onClick = { onSelect(mode) }
            )
        }
        OutlinedButton(
            onClick = onSave,
            enabled = selected != saved,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
        ) { Text(if (selected == saved) "Microphone choice saved" else "Save microphone choice") }
    }
}

@Composable
private fun AiSettingsPanel(
    provider: LlmProvider,
    model: String,
    apiKey: String,
    connectionStatus: String,
    onProviderChange: (LlmProvider) -> Unit,
    onModelChange: (String) -> Unit,
    onApiKeyChange: (String) -> Unit,
    onSave: () -> Unit,
    onTestConnection: () -> Unit
) {
    SettingsSection("AI summaries", "Optional. Connect AI to turn transcripts into summaries and action items.", NoteIcon.NOTES) {
        Text("Provider", style = MaterialTheme.typography.titleMedium)
        LlmProvider.entries.forEach { option ->
            SelectionRow(option.displayName, selected = provider == option) { onProviderChange(option) }
        }
        OutlinedTextField(
            value = model, onValueChange = onModelChange,
            label = { Text("AI model") }, singleLine = true,
            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)
        )
        OutlinedTextField(
            value = apiKey, onValueChange = onApiKeyChange,
            label = { Text("API key") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrect = false),
            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)
        )
        Text("Leave blank to keep your saved key. Summaries send transcript text to your selected provider.", style = MaterialTheme.typography.bodySmall, color = Muted)
        Button(
            onClick = onSave,
            modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp), shape = RoundedCornerShape(16.dp)
        ) { Text("Save AI settings") }
        OutlinedButton(
            onClick = onTestConnection,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = RoundedCornerShape(16.dp)
        ) { Text("Test connection") }
        Text(connectionStatus, style = MaterialTheme.typography.bodySmall, color = Muted)
    }
}

@Composable
private fun BrandHeader() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
        Box(Modifier.size(39.dp).background(Ink, RoundedCornerShape(13.dp)), contentAlignment = Alignment.Center) {
            LineIcon(NoteIcon.WAVE, Color.White, Modifier.size(23.dp))
        }
        Text("Live Notes", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.weight(1f))
        Text(LocalDate.now().format(DateTimeFormatter.ofPattern("MMM d", Locale.getDefault())), style = MaterialTheme.typography.bodySmall, color = Muted)
    }
}

@Composable
private fun PageHeading(title: String, subtitle: String) {
    Column(Modifier.padding(top = 9.dp, bottom = 2.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.headlineLarge)
        Text(subtitle, color = Muted, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SummaryCard(note: DailyNote?, compact: Boolean = false, onExport: (String) -> Unit) {
    val context = LocalContext.current
    NoteSurface {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            LineIcon(NoteIcon.NOTES, Teal, Modifier.size(21.dp))
            Text("The key takeaways", style = MaterialTheme.typography.titleMedium)
        }
        Text(
            note?.summary?.ifBlank { "Your summary will appear here after a conversation." }
                ?: "Your summary will appear here after a conversation. AI summaries can be connected in Settings.",
            color = if (note == null) Muted else Ink,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = if (compact) 5 else Int.MAX_VALUE,
            overflow = TextOverflow.Ellipsis
        )
        if (!note?.actionItems.isNullOrEmpty()) {
            SectionDivider()
            Text("NEXT STEPS", style = MaterialTheme.typography.labelMedium, color = Muted, letterSpacing = 1.sp)
            note!!.actionItems.take(if (compact) 3 else Int.MAX_VALUE).forEach { ActionItem(it) }
            if (compact && note.actionItems.size > 3) {
                Text("+ ${note.actionItems.size - 3} more in Notes", style = MaterialTheme.typography.bodySmall, color = Teal)
            }
        }
        if (note != null) {
            val text = noteText(note)
            TextActions(text,
                onCopy = { RecordingSharing.copyText(context, text, "Meeting notes") },
                onShare = { RecordingSharing.shareText(context, text, "Notes · ${note.dateKey}") },
                onExport = { onExport(text) })
        }
    }
}

private fun noteText(note: DailyNote): String = buildString {
    append(note.summary)
    if (note.actionItems.isNotEmpty()) {
        if (isNotEmpty()) append("\n\n")
        append("Next steps\n")
        append(note.actionItems.joinToString("\n") { "• $it" })
    }
}

@Composable
private fun HistoryCard(note: DailyNote, onExport: (String) -> Unit) {
    val context = LocalContext.current
    var expanded by rememberSaveable(note.dateKey) { mutableStateOf(false) }
    NoteSurface {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text(friendlyDate(note.dateKey), style = MaterialTheme.typography.titleMedium)
            if (note.actionItems.isNotEmpty()) Badge("${note.actionItems.size} ${if (note.actionItems.size == 1) "TASK" else "TASKS"}")
        }
        SelectionContainer {
            Text(
                note.summary.ifBlank { "No summary yet." },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = if (expanded) Int.MAX_VALUE else 5,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (expanded && note.actionItems.isNotEmpty()) {
            SectionDivider()
            Text("Next steps", style = MaterialTheme.typography.titleMedium)
            note.actionItems.forEach { ActionItem(it) }
        }
        val shareableText = noteText(note)
        TextActions(shareableText,
            onCopy = { RecordingSharing.copyText(context, shareableText, "Meeting notes") },
            onShare = { RecordingSharing.shareText(context, shareableText, "Notes · ${note.dateKey}") },
            onExport = { onExport(shareableText) })
        TextButton(onClick = { expanded = !expanded }, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(if (expanded) "Show less" else "Read note")
            Spacer(Modifier.width(6.dp))
            LineIcon(NoteIcon.ARROW, Teal, Modifier.size(18.dp))
        }
    }
}

@Composable
private fun ActionItem(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.padding(top = 6.dp).size(8.dp).background(Teal, CircleShape))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun AttentionCard(title: String, message: String, action: String? = null, onAction: (() -> Unit)? = null) {
    NoteSurface(color = ErrorPaper) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = ErrorInk)
        Text(message, style = MaterialTheme.typography.bodyMedium, color = ErrorInk)
        if (action != null && onAction != null) {
            Text("Your transcript is kept on this device.", style = MaterialTheme.typography.bodySmall, color = ErrorInk)
            TextButton(onClick = onAction, modifier = Modifier.heightIn(min = 48.dp)) { Text(action, color = ErrorInk) }
        }
    }
}

@Composable
private fun SettingsSection(title: String, subtitle: String, icon: NoteIcon, content: @Composable ColumnScope.() -> Unit) {
    NoteSurface {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LineIcon(icon, Teal)
            Text(title, style = MaterialTheme.typography.titleLarge)
        }
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Muted)
        content()
    }
}

@Composable
private fun SpeechModelOption(
    model: SpeechModel,
    selected: Boolean,
    installed: Boolean,
    downloadState: ModelDownloadState,
    isDownloadTarget: Boolean,
    canRemove: Boolean,
    onDownload: () -> Unit,
    onUse: () -> Unit,
    onRemove: () -> Unit
) {
    val busy = downloadState is ModelDownloadState.Downloading || downloadState is ModelDownloadState.CheckingExisting
    Column(
        Modifier.fillMaxWidth().background(if (selected) SoftTeal else Paper, RoundedCornerShape(18.dp))
            .border(1.dp, if (selected) Teal.copy(alpha = 0.35f) else Line, RoundedCornerShape(18.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            if (selected) Badge("SELECTED")
            else if (installed) Badge("INSTALLED")
            else model.recommendation?.let { Badge(it.uppercase(Locale.getDefault())) }
            Text(model.title, style = MaterialTheme.typography.titleMedium)
            Text(model.description, style = MaterialTheme.typography.bodySmall, color = Muted)
            Text("${model.approxSizeMb} MB download · On device", style = MaterialTheme.typography.bodySmall, color = Muted)
        }
        if (isDownloadTarget) {
            when (downloadState) {
                is ModelDownloadState.Downloading -> {
                    if (downloadState.totalBytes > 0) {
                        LinearProgressIndicator(progress = { downloadState.progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    } else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(
                        "${downloadState.bytesDownloaded / 1_000_000} MB downloaded" +
                            if (downloadState.totalBytes > 0) " of ${downloadState.totalBytes / 1_000_000} MB" else "",
                        style = MaterialTheme.typography.bodySmall, color = Muted
                    )
                }
                is ModelDownloadState.CheckingExisting -> Text("Checking download…", color = Muted, style = MaterialTheme.typography.bodySmall)
                is ModelDownloadState.Failed -> Text(downloadState.message, color = ErrorInk, style = MaterialTheme.typography.bodySmall)
                else -> Unit
            }
        }
        if (installed) {
            if (!selected) {
                Button(onClick = onUse, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = RoundedCornerShape(13.dp)) { Text("Use this model") }
            }
            TextButton(onClick = onRemove, enabled = !busy && canRemove, modifier = Modifier.heightIn(min = 48.dp)) { Text("Remove download") }
        } else {
            OutlinedButton(onClick = onDownload, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = RoundedCornerShape(13.dp)) {
                Text(if (busy && isDownloadTarget) "Downloading…" else if (isDownloadTarget && downloadState is ModelDownloadState.Failed) "Retry download" else "Download model")
            }
        }
    }
}

@Composable
private fun SelectionRow(title: String, description: String? = null, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().background(if (selected) SoftTeal else Color.Transparent, RoundedCornerShape(14.dp))
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick).heightIn(min = 56.dp).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        RadioButton(selected = selected, onClick = null, modifier = Modifier.size(24.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
            description?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Muted) }
        }
    }
}

@Composable
private fun LanguageSelector(selected: SpeechLanguage, languages: List<SpeechLanguage>, onSelect: (SpeechLanguage) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(
            onClick = { expanded = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            shape = RoundedCornerShape(14.dp)
        ) {
            Text(selected.label)
            Spacer(Modifier.weight(1f))
            Text("Change", style = MaterialTheme.typography.bodySmall)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.heightIn(max = 320.dp)) {
            languages.forEach { language ->
                DropdownMenuItem(
                    text = { Text(language.label, fontWeight = if (language == selected) FontWeight.SemiBold else FontWeight.Normal) },
                    onClick = { onSelect(language); expanded = false }, modifier = Modifier.heightIn(min = 48.dp)
                )
            }
        }
    }
}

@Composable
private fun EmptyState(title: String, body: String, icon: NoteIcon) {
    NoteSurface {
        Box(Modifier.size(52.dp).background(SoftTeal, RoundedCornerShape(17.dp)), contentAlignment = Alignment.Center) {
            LineIcon(icon, Teal, Modifier.size(26.dp))
        }
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(body, color = Muted, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun NoteSurface(color: Color = Color.White, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = color), shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(13.dp), content = content)
    }
}

@Composable
private fun Badge(text: String) {
    Text(text, modifier = Modifier.background(SoftTeal, RoundedCornerShape(7.dp)).padding(horizontal = 8.dp, vertical = 4.dp),
        style = MaterialTheme.typography.labelMedium, color = Teal, letterSpacing = 0.4.sp)
}

@Composable
private fun SectionDivider() {
    Spacer(Modifier.fillMaxWidth().height(1.dp).background(Line))
}

private fun friendlyDate(dateKey: String): String = runCatching {
    val date = LocalDate.parse(dateKey)
    when (date) {
        LocalDate.now() -> "Today"
        LocalDate.now().minusDays(1) -> "Yesterday"
        else -> date.format(DateTimeFormatter.ofPattern(if (date.year == LocalDate.now().year) "EEE, MMM d" else "MMM d, yyyy", Locale.getDefault()))
    }
}.getOrDefault(dateKey)

private enum class NoteIcon { MIC, NOTES, SETTINGS, WAVE, STOP, ARROW }

/** Small native line icons keep the application independent of external assets. */
@Composable
private fun LineIcon(icon: NoteIcon, color: Color, modifier: Modifier = Modifier.size(24.dp)) {
    Canvas(modifier) {
        withTransform({ scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) }) {
            val stroke = Stroke(width = 1.8f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(color, Offset(x1, y1), Offset(x2, y2), strokeWidth = 1.8f, cap = StrokeCap.Round)
            when (icon) {
                NoteIcon.MIC -> {
                    drawRoundRect(color, Offset(8f, 2f), Size(8f, 13f), CornerRadius(4f), style = stroke)
                    drawPath(Path().apply { moveTo(5f, 10f); cubicTo(5f, 21f, 19f, 21f, 19f, 10f) }, color, style = stroke)
                    line(12f, 18f, 12f, 22f); line(8f, 22f, 16f, 22f)
                }
                NoteIcon.NOTES -> {
                    drawRoundRect(color, Offset(4f, 3f), Size(16f, 19f), CornerRadius(3f), style = stroke)
                    line(8f, 8f, 16f, 8f); line(8f, 12f, 16f, 12f); line(8f, 16f, 13f, 16f)
                }
                NoteIcon.SETTINGS -> {
                    line(5f, 3f, 5f, 21f); line(12f, 3f, 12f, 21f); line(19f, 3f, 19f, 21f)
                    drawCircle(Paper, 2.7f, Offset(5f, 8f)); drawCircle(color, 2.7f, Offset(5f, 8f), style = stroke)
                    drawCircle(Paper, 2.7f, Offset(12f, 16f)); drawCircle(color, 2.7f, Offset(12f, 16f), style = stroke)
                    drawCircle(Paper, 2.7f, Offset(19f, 10f)); drawCircle(color, 2.7f, Offset(19f, 10f), style = stroke)
                }
                NoteIcon.WAVE -> listOf(7f, 13f, 20f, 13f, 7f).forEachIndexed { index, height -> line(4f + index * 4f, 12f - height / 2, 4f + index * 4f, 12f + height / 2) }
                NoteIcon.STOP -> drawRoundRect(color, Offset(5f, 5f), Size(14f, 14f), CornerRadius(3f))
                NoteIcon.ARROW -> { line(4f, 12f, 20f, 12f); line(14f, 6f, 20f, 12f); line(14f, 18f, 20f, 12f) }
            }
        }
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 844, fontScale = 1f)
@Composable
private fun RecordPreview() {
    LiveNotesTheme {
        Surface(color = Paper) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                BrandHeader()
                PageHeading("Space for your thoughts.", "A conversation today. Something to remember tomorrow.")
                RecorderHero(CapturePhase.IDLE, 0L, 0f, "", "Android speech", "Not listening") {}
                LiveTranscriptPanel(CapturePhase.IDLE, "", emptyList(), {}, {}, {})
            }
        }
    }
}
