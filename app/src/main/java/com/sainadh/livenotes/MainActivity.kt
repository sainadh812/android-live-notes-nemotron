package com.sainadh.livenotes

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sainadh.livenotes.ai.LlmProvider
import com.sainadh.livenotes.audio.AudioInputMode
import com.sainadh.livenotes.data.DailyNote
import com.sainadh.livenotes.service.ServiceStateTracker
import com.sainadh.livenotes.stt.ModelDownloadState
import com.sainadh.livenotes.stt.NemotronQuant

class MainActivity : ComponentActivity() {
    companion object {
        const val EXTRA_AUTO_TEST_OPENAI = "extra_auto_test_openai"
    }

    private val viewModel by viewModels<MainViewModel> {
        MainViewModel.Factory(application as LiveNotesApplication)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val activityLifecycle = lifecycle
        setContent {
            MaterialTheme {
                LiveNotesScreen(
                    viewModel = viewModel,
                    activityLifecycle = activityLifecycle
                )
            }
        }
        if (intent.getBooleanExtra(EXTRA_AUTO_TEST_OPENAI, false)) {
            viewModel.testConnection(
                provider = viewModel.currentProvider(),
                model = viewModel.currentModel(),
                apiKey = ""
            )
        }
    }
}

@Composable
private fun LiveNotesScreen(
    viewModel: MainViewModel,
    activityLifecycle: Lifecycle
) {
    val context = LocalContext.current
    val todayNote by viewModel.todayNote.collectAsStateWithLifecycle(lifecycle = activityLifecycle)
    val allNotes by viewModel.allNotes.collectAsStateWithLifecycle(lifecycle = activityLifecycle)
    val isListening by viewModel.isListening.collectAsStateWithLifecycle(lifecycle = activityLifecycle)
    val latestTranscript by viewModel.latestTranscript.collectAsStateWithLifecycle(lifecycle = activityLifecycle)
    val connectionStatus by viewModel.connectionStatus.collectAsStateWithLifecycle(lifecycle = activityLifecycle)
    val currentAudioRoute by ServiceStateTracker.audioRoute.collectAsStateWithLifecycle(lifecycle = activityLifecycle)
    val lastSummaryError by ServiceStateTracker.lastSummaryError.collectAsStateWithLifecycle(lifecycle = activityLifecycle)
    val modelDownloadState by viewModel.modelDownloadState.collectAsStateWithLifecycle(lifecycle = activityLifecycle)
    var apiKey by rememberSaveable { mutableStateOf("") }
    var selectedProvider by rememberSaveable { mutableStateOf(viewModel.currentProvider()) }
    var selectedModel by rememberSaveable { mutableStateOf(viewModel.currentModel()) }
    var selectedAudioInputMode by rememberSaveable { mutableStateOf(viewModel.currentAudioInputMode()) }
    var showSettings by rememberSaveable { mutableStateOf(!viewModel.hasApiKey()) }
    var selectedQuant by rememberSaveable { mutableStateOf(viewModel.downloadedQuant() ?: NemotronQuant.default) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        val allGranted = granted.values.all { it }
        if (allGranted) {
            viewModel.toggleListening()
        }
    }

    fun requiredPermissions(): Array<String> {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms += Manifest.permission.POST_NOTIFICATIONS
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms += Manifest.permission.BLUETOOTH_CONNECT
        }
        return perms.toTypedArray()
    }

    fun canListenNow(): Boolean {
        return requiredPermissions().all { permission ->
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = Color(0xFFF7FAFC)
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                HeaderCard(
                    isListening = isListening,
                    currentAudioRoute = currentAudioRoute,
                    onToggle = {
                        if (canListenNow()) {
                            viewModel.toggleListening()
                        } else {
                            permissionLauncher.launch(requiredPermissions())
                        }
                    }
                )
            }
            item {
                LiveTranscriptCard(
                    isListening = isListening,
                    latestTranscript = latestTranscript
                )
            }
            if (!lastSummaryError.isNullOrBlank()) {
                item { SummaryErrorCard(message = lastSummaryError!!) }
            }
            item {
                OnDeviceModelCard(
                    downloadState = modelDownloadState,
                    selectedQuant = selectedQuant,
                    downloadedQuant = viewModel.downloadedQuant(),
                    onQuantChange = { selectedQuant = it },
                    onDownload = { viewModel.downloadModel(selectedQuant) },
                    onDelete = { viewModel.deleteModel(selectedQuant) }
                )
            }
            item { TodaySummaryCard(todayNote = todayNote) }
            item { ActionItemsCard(todayNote = todayNote) }
            item {
                SettingsCard(
                    expanded = showSettings,
                    apiKey = apiKey,
                    selectedProvider = selectedProvider,
                    selectedModel = selectedModel,
                    selectedAudioInputMode = selectedAudioInputMode,
                    connectionStatus = connectionStatus,
                    onExpandedChange = { showSettings = !showSettings },
                    onApiKeyChange = { apiKey = it },
                    onProviderChange = { provider ->
                        selectedProvider = provider
                        selectedModel = provider.defaultModel
                    },
                    onModelChange = { selectedModel = it },
                    onAudioInputModeChange = { selectedAudioInputMode = it },
                    onSave = {
                        viewModel.saveSettings(
                            provider = selectedProvider,
                            model = selectedModel,
                            apiKey = apiKey,
                            audioInputMode = selectedAudioInputMode
                        )
                    },
                    onTestConnection = {
                        viewModel.testConnection(
                            provider = selectedProvider,
                            model = selectedModel,
                            apiKey = apiKey
                        )
                    }
                )
            }
            item {
                Text(
                    text = "Browse notes by date",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            items(allNotes) { note ->
                HistoryCard(note = note)
            }
        }
    }
}

@Composable
private fun HeaderCard(
    isListening: Boolean,
    currentAudioRoute: String,
    onToggle: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Live meeting notes", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(
                        if (isListening) "Background capture is active" else "Capture is stopped",
                        color = Color(0xFF475569)
                    )
                    Text(
                        text = "Input: $currentAudioRoute",
                        color = Color(0xFF0F766E)
                    )
                }
                Switch(checked = isListening, onCheckedChange = { onToggle() })
            }
        }
    }
}

/**
 * Dedicated, visually prominent live-transcript panel - separate from
 * HeaderCard so it can't be mistaken for a static subtitle line. Shows a
 * clear "waiting for speech" state distinct from "listening but nothing
 * transcribed yet" so a stalled transcriber is visible, not just blank.
 */
@Composable
private fun LiveTranscriptCard(isListening: Boolean, latestTranscript: String) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isListening) Color(0xFFECFDF5) else Color.White
        ),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Live transcript", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                if (isListening) {
                    Text("\u25CF listening", color = Color(0xFF059669), fontWeight = FontWeight.SemiBold)
                }
            }
            Text(
                text = when {
                    latestTranscript.isNotBlank() -> latestTranscript
                    isListening -> "Listening... say something and text will appear here."
                    else -> "Not listening. Toggle the switch above to start."
                },
                style = MaterialTheme.typography.bodyLarge,
                color = if (latestTranscript.isBlank()) Color(0xFF94A3B8) else Color(0xFF0F172A)
            )
        }
    }
}

@Composable
private fun SummaryErrorCard(message: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFEE2E2)),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Note summarization stopped", fontWeight = FontWeight.SemiBold, color = Color(0xFF991B1B))
            Text(message, color = Color(0xFF7F1D1D))
            Text(
                "Transcript is still being captured. Check your API key or connection, then keep talking to retry.",
                color = Color(0xFF991B1B)
            )
        }
    }
}

@Composable
private fun OnDeviceModelCard(
    downloadState: ModelDownloadState,
    selectedQuant: NemotronQuant,
    downloadedQuant: NemotronQuant?,
    onQuantChange: (NemotronQuant) -> Unit,
    onDownload: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("On-device speech model (Nemotron 3.5)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "Download once to transcribe fully on-device instead of using the OS speech "
                    + "recognizer. Restart listening after a download finishes to switch to it.",
                color = Color(0xFF475569)
            )

            if (downloadedQuant != null) {
                Text(
                    "Active on-device model: ${downloadedQuant.label}",
                    color = Color(0xFF0F766E),
                    fontWeight = FontWeight.SemiBold
                )
            }

            Text("Quantization", fontWeight = FontWeight.SemiBold)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                NemotronQuant.entries.forEach { quant ->
                    val selected = selectedQuant == quant
                    val isDownloadedThis = downloadedQuant == quant
                    Button(
                        onClick = { onQuantChange(quant) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        val prefix = when {
                            isDownloadedThis -> "\u2713 (downloaded) "
                            selected -> "\u2713 "
                            else -> ""
                        }
                        Text("$prefix${quant.label} \u2014 FLEURS WER ${quant.fleursWer}%")
                    }
                }
            }

            when (downloadState) {
                is ModelDownloadState.Downloading -> {
                    val mbDone = downloadState.bytesDownloaded / 1024 / 1024
                    val mbTotal = downloadState.totalBytes / 1024 / 1024
                    LinearProgressIndicator(
                        progress = { downloadState.progress },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text("Downloading... $mbDone / $mbTotal MB", color = Color(0xFF475569))
                }
                is ModelDownloadState.Failed -> {
                    Text("Download failed: ${downloadState.message}", color = Color(0xFF991B1B))
                }
                is ModelDownloadState.Completed -> {
                    Text("Download complete. Restart listening to use it.", color = Color(0xFF0F766E))
                }
                else -> Unit
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val isBusy = downloadState is ModelDownloadState.Downloading
                    || downloadState is ModelDownloadState.CheckingExisting
                Button(onClick = onDownload, enabled = !isBusy) {
                    Text(
                        when (downloadState) {
                            is ModelDownloadState.Failed -> "Retry download"
                            else -> "Download ${selectedQuant.label}"
                        }
                    )
                }
                if (downloadedQuant == selectedQuant) {
                    TextButton(onClick = onDelete) {
                        Text("Delete")
                    }
                }
            }
        }
    }
}

@Composable
private fun TodaySummaryCard(todayNote: DailyNote?) {
    NoteCard(
        title = "Today's summary",
        body = todayNote?.summary?.ifBlank { "No summary yet." } ?: "No summary yet."
    )
}

@Composable
private fun ActionItemsCard(todayNote: DailyNote?) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Action items", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            val items = todayNote?.actionItems.orEmpty()
            if (items.isEmpty()) {
                Text("No action items extracted yet.")
            } else {
                items.forEachIndexed { index, item ->
                    Text("${index + 1}. $item")
                }
            }
        }
    }
}

@Composable
private fun SettingsCard(
    expanded: Boolean,
    apiKey: String,
    selectedProvider: LlmProvider,
    selectedModel: String,
    selectedAudioInputMode: AudioInputMode,
    connectionStatus: String,
    onExpandedChange: () -> Unit,
    onApiKeyChange: (String) -> Unit,
    onProviderChange: (LlmProvider) -> Unit,
    onModelChange: (String) -> Unit,
    onAudioInputModeChange: (AudioInputMode) -> Unit,
    onSave: () -> Unit,
    onTestConnection: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("AI + microphone settings", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                TextButton(onClick = onExpandedChange) {
                    Text(if (expanded) "Hide" else "Show")
                }
            }
            if (expanded) {
                Text("Provider", fontWeight = FontWeight.SemiBold)
                ProviderSelector(selectedProvider = selectedProvider, onProviderChange = onProviderChange)
                OutlinedTextField(
                    value = selectedModel,
                    onValueChange = onModelChange,
                    label = { Text("Model") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = onApiKeyChange,
                    label = { Text("API key") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Text(
                    text = "Leave API key blank to use the embedded default or keep the key already saved on the device.",
                    color = Color(0xFF475569)
                )
                Text("Microphone source", fontWeight = FontWeight.SemiBold)
                AudioInputSelector(
                    selectedAudioInputMode = selectedAudioInputMode,
                    onAudioInputModeChange = onAudioInputModeChange
                )
                Text(
                    text = "Auto uses Bluetooth when available, otherwise the phone microphone.",
                    color = Color(0xFF475569)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = onSave) {
                        Text("Save securely")
                    }
                    TextButton(onClick = onTestConnection) {
                        Text("Test AI connection")
                    }
                }
                Text(connectionStatus, color = Color(0xFF0F172A))
            }
        }
    }
}

@Composable
private fun ProviderSelector(selectedProvider: LlmProvider, onProviderChange: (LlmProvider) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LlmProvider.entries.forEach { provider ->
            val selected = selectedProvider == provider
            Button(onClick = { onProviderChange(provider) }, modifier = Modifier.fillMaxWidth()) {
                Text(if (selected) "✓ ${provider.displayName}" else provider.displayName)
            }
        }
    }
}

@Composable
private fun AudioInputSelector(
    selectedAudioInputMode: AudioInputMode,
    onAudioInputModeChange: (AudioInputMode) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AudioInputMode.entries.forEach { mode ->
            val selected = selectedAudioInputMode == mode
            Button(onClick = { onAudioInputModeChange(mode) }, modifier = Modifier.fillMaxWidth()) {
                Text(if (selected) "✓ ${mode.displayName}" else mode.displayName)
            }
        }
    }
}

@Composable
private fun HistoryCard(note: DailyNote) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(18.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(note.dateKey, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(note.summary.ifBlank { "No summary available" })
            if (note.actionItems.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                note.actionItems.forEach { item -> Text("• $item") }
            }
        }
    }
}

@Composable
private fun NoteCard(title: String, body: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(body)
        }
    }
}
