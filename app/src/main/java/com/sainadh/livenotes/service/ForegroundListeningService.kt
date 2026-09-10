package com.sainadh.livenotes.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.sainadh.livenotes.LiveNotesApplication
import com.sainadh.livenotes.MainActivity
import com.sainadh.livenotes.R
import com.sainadh.livenotes.audio.BluetoothAudioRouter
import com.sainadh.livenotes.stt.NemotronTranscriber
import com.sainadh.livenotes.stt.SpeechModel
import com.sainadh.livenotes.stt.SpeechTranscriber
import com.sainadh.livenotes.stt.TranscriptStatus
import com.sainadh.livenotes.stt.TranscriptUpdate
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class CapturePhase { IDLE, PREPARING, RECORDING, FINISHING }

object ServiceStateTracker {
    val capturePhase = MutableStateFlow(CapturePhase.IDLE)
    val activeEngine = MutableStateFlow("Android speech")
    val listening = MutableStateFlow(false)
    val latestTranscript = MutableStateFlow("")
    val audioRoute = MutableStateFlow("Not listening")
    val lastTranscriptionError = MutableStateFlow<String?>(null)
    val recordingId = MutableStateFlow<String?>(null)
    val durationMs = MutableStateFlow(0L)
    val audioLevel = MutableStateFlow(0f)
    val liveSegments = MutableStateFlow<List<TranscriptUpdate>>(emptyList())
    val audioNotice = MutableStateFlow<String?>(null)
}

class ForegroundListeningService : Service() {
    private enum class Phase { IDLE, PREPARING, LISTENING, STOPPING, DRAINING, DESTROYED }

    // Callbacks and lifecycle changes run on main. Transcript work runs in callback
    // order on IO, and shutdown waits until the final transcript has been saved.
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var lastTranscriptJob: Job? = null
    private var phase = Phase.IDLE
    private var generation = 0
    private var lastStartId = 0
    private var restartRequested = false
    private var notificationActive = false
    private var speechTranscriber: SpeechTranscriber? = null
    private var nemotronTranscriber: NemotronTranscriber? = null
    private var usingNemotron = false
    private var leasedModel: SpeechModel? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var bluetoothAudioRouter: BluetoothAudioRouter? = null
    private var activeRecordingId: String? = null
    private var recordingCreated = false
    private var savedAudioFileName: String? = null
    private var savedAudioDurationMs: Long? = null
    private val sessionErrors = linkedSetOf<String>()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        bluetoothAudioRouter = BluetoothAudioRouter(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        when (intent?.action) {
            ACTION_STOP -> stopListeningAndSelf()
            else -> startListening()
        }
        return if (intent?.action == ACTION_STOP) START_NOT_STICKY else START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun resolveTranscriber(listener: SpeechTranscriber.Listener, audioFile: File) {
        val app = application as LiveNotesApplication
        val downloadManager = app.appContainer.modelDownloadManager
        val settings = app.appContainer.speechSettings.state.value
        val model = settings.model
        usingNemotron = model != null
        ServiceStateTracker.activeEngine.value = model?.title ?: "Android speech"
        if (model != null) {
            check(downloadManager.isDownloaded(model)) {
                "${model.title} is missing or incomplete. Download it in Settings, or select Android speech."
            }
            check(NemotronTranscriber.isAvailable()) {
                "On-device speech could not load (${NemotronTranscriber.loadError()}). Select Android speech in Settings to use your phone’s recognizer."
            }
            check(downloadManager.acquireForCapture(model)) {
                "${model.title} is unavailable. Download it in Settings, then try again."
            }
            leasedModel = model
            nemotronTranscriber = NemotronTranscriber(
                context = this,
                modelPath = downloadManager.modelFile(model).absolutePath,
                language = settings.language.code,
                listener = listener,
                audioFile = audioFile
            )
        } else {
            speechTranscriber = SpeechTranscriber(this, listener, audioFile)
        }
    }

    private fun startListening() {
        when (phase) {
            Phase.PREPARING, Phase.LISTENING, Phase.DESTROYED -> return
            Phase.STOPPING, Phase.DRAINING -> {
                restartRequested = true
                return
            }
            Phase.IDLE -> Unit
        }
        phase = Phase.PREPARING
        ServiceStateTracker.capturePhase.value = CapturePhase.PREPARING
        val sessionGeneration = ++generation
        val recordingId = UUID.randomUUID().toString()
        val startedAtMs = System.currentTimeMillis()
        activeRecordingId = recordingId
        recordingCreated = false
        savedAudioFileName = null
        savedAudioDurationMs = null
        sessionErrors.clear()
        ServiceStateTracker.lastTranscriptionError.value = null
        ServiceStateTracker.latestTranscript.value = ""
        ServiceStateTracker.recordingId.value = recordingId
        ServiceStateTracker.durationMs.value = 0L
        ServiceStateTracker.audioLevel.value = 0f
        ServiceStateTracker.liveSegments.value = emptyList()
        ServiceStateTracker.audioNotice.value = null
        try {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(getString(R.string.notification_listening_title), "Preparing microphone")
            )
            notificationActive = true
        } catch (error: RuntimeException) {
            recordError(error.message ?: "Could not start microphone capture")
            finishCapture(sessionGeneration)
            return
        }
        serviceScope.launch {
            try {
                val app = application as LiveNotesApplication
                // Recovery must never inspect files belonging to this new session.
                app.appContainer.recordingRecovery.await()
                if (generation != sessionGeneration || phase == Phase.DESTROYED) return@launch
                if (phase != Phase.PREPARING) {
                    finishCapture(sessionGeneration)
                    return@launch
                }
                withContext(Dispatchers.IO) {
                    app.appContainer.repository.beginRecording(recordingId, startedAtMs)
                }
                recordingCreated = true
                // A stop can arrive while the database insert is in progress. Finish
                // that row, but do not acquire a model or start the microphone.
                if (generation != sessionGeneration || phase == Phase.DESTROYED) return@launch
                if (phase != Phase.PREPARING) {
                    finishCapture(sessionGeneration)
                    return@launch
                }
                resolveTranscriber(callbacks(sessionGeneration, recordingId),
                    File(filesDir, "recordings/$recordingId.wav"))
                val inputMode = app.appContainer.secureSettings.readAudioInputMode()
                val route = bluetoothAudioRouter?.activate(inputMode) ?: "Phone microphone"
                ServiceStateTracker.audioRoute.value = route
                ServiceStateTracker.listening.value = true
                acquireWakeLock()
                phase = Phase.LISTENING
                updateNotification("Preparing ${ServiceStateTracker.activeEngine.value} · $route")
                if (usingNemotron) nemotronTranscriber?.start() else speechTranscriber?.start()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (generation != sessionGeneration || phase == Phase.DESTROYED) return@launch
                recordError(error.message ?: "Could not prepare recording")
                finishCapture(sessionGeneration)
            }
        }
    }

    private fun callbacks(sessionGeneration: Int, recordingId: String) = object : SpeechTranscriber.Listener {
        private val displaySegments = sortedMapOf<Long, TranscriptUpdate>()
        private var legacySegmentId = 0L
        private fun acceptsCallbacks() = generation == sessionGeneration &&
            (phase == Phase.LISTENING || phase == Phase.STOPPING)

        override fun onTranscript(text: String, isFinal: Boolean) {
            // Compatibility for listener clients that have not adopted segment events.
            onTranscriptUpdate(TranscriptUpdate(legacySegmentId, text,
                if (isFinal) TranscriptStatus.FINAL else TranscriptStatus.PARTIAL))
            if (isFinal) legacySegmentId += 1L
        }

        override fun onTranscriptUpdate(update: TranscriptUpdate) {
            if (!acceptsCallbacks()) return
            val previousSegment = displaySegments[update.segmentId]
            if (previousSegment != null && previousSegment.status != TranscriptStatus.PARTIAL) return
            displaySegments[update.segmentId] = update
            val text = buildString {
                displaySegments.values.forEach { segment ->
                    if (segment.text.isNotEmpty()) {
                        if (isNotEmpty() && !segment.appendToPrevious) append('\n')
                        append(segment.text)
                    }
                }
            }
            ServiceStateTracker.latestTranscript.value = text
            ServiceStateTracker.liveSegments.value = displaySegments.values.toList()
            val timestampMs = System.currentTimeMillis()
            val previous = lastTranscriptJob
            lastTranscriptJob = serviceScope.launch {
                previous?.join()
                try {
                    val app = application as LiveNotesApplication
                    val result = withContext(Dispatchers.IO) {
                        app.appContainer.conversationOrchestrator.onTranscript(recordingId, update, timestampMs)
                    }
                    result.exceptionOrNull()?.let {
                        recordError("Could not save transcript: ${it.message ?: it.javaClass.simpleName}")
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    recordError("Could not save transcript: ${error.message ?: error.javaClass.simpleName}")
                }
            }
            if (phase == Phase.LISTENING) updateNotification(text)
        }

        override fun onStateChanged(state: String) {
            if (!acceptsCallbacks()) return
            if (state == "stopped") {
                finishCapture(sessionGeneration)
            } else if (state == "finishing") {
                phase = Phase.STOPPING
                ServiceStateTracker.capturePhase.value = CapturePhase.FINISHING
                ServiceStateTracker.listening.value = false
                updateNotification("Finishing transcription")
            } else if (phase == Phase.LISTENING) {
                if (state == "ready" || state == "listening") {
                    ServiceStateTracker.capturePhase.value = CapturePhase.RECORDING
                } else if (state == "loading model" || state == "restarting") {
                    ServiceStateTracker.capturePhase.value = CapturePhase.PREPARING
                }
                updateNotification(state)
            }
        }

        override fun onError(reason: String) {
            if (!acceptsCallbacks()) return
            recordError(reason)
            updateNotification(reason)
        }

        override fun onAudioProgress(durationMs: Long, level: Float) {
            if (!acceptsCallbacks()) return
            ServiceStateTracker.durationMs.value = maxOf(ServiceStateTracker.durationMs.value, durationMs)
            ServiceStateTracker.audioLevel.value = if (level.isFinite()) level.coerceIn(0f, 1f) else 0f
        }

        override fun onAudioSaved(fileName: String, durationMs: Long) {
            if (!acceptsCallbacks()) return
            savedAudioFileName = fileName
            savedAudioDurationMs = durationMs.coerceAtLeast(0L)
            ServiceStateTracker.durationMs.value = durationMs.coerceAtLeast(0L)
        }

        override fun onAudioUnavailable(reason: String) {
            if (!acceptsCallbacks()) return
            ServiceStateTracker.audioNotice.value = reason
        }
    }

    private fun stopListeningAndSelf() {
        restartRequested = false
        ServiceStateTracker.listening.value = false
        when (phase) {
            Phase.IDLE -> {
                removeNotification()
                stopSelfResult(lastStartId)
            }
            Phase.PREPARING -> {
                phase = Phase.STOPPING
                ServiceStateTracker.capturePhase.value = CapturePhase.FINISHING
                updateNotification("Finishing recording")
                // The preparation coroutine owns completion of any pending insert.
            }
            Phase.LISTENING -> {
                phase = Phase.STOPPING
                ServiceStateTracker.capturePhase.value = CapturePhase.FINISHING
                updateNotification("Finishing transcription")
                // Both transcribers emit their final result before a stopped callback.
                // Do not destroy them or cancel pending writes until that arrives.
                if (usingNemotron) nemotronTranscriber?.stop() else speechTranscriber?.stop()
            }
            else -> Unit
        }
    }

    private fun finishCapture(sessionGeneration: Int) {
        if (generation != sessionGeneration || phase == Phase.DRAINING || phase == Phase.DESTROYED) return
        phase = Phase.DRAINING
        ServiceStateTracker.capturePhase.value = CapturePhase.FINISHING
        ServiceStateTracker.listening.value = false
        releaseAudioResources()
        updateNotification("Saving recording")
        val finalWrite = lastTranscriptJob
        val recordingId = activeRecordingId.takeIf { recordingCreated }
        val audioFileName = savedAudioFileName
        val durationMs = savedAudioDurationMs ?: ServiceStateTracker.durationMs.value
        serviceScope.launch {
            finalWrite?.join()
            if (generation != sessionGeneration || phase != Phase.DRAINING) return@launch
            if (recordingId != null) {
                try {
                    withContext(Dispatchers.IO) {
                        (application as LiveNotesApplication).appContainer.repository
                            .finishRecording(recordingId, audioFileName, durationMs)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    recordError("Could not save recording: ${error.message ?: error.javaClass.simpleName}")
                }
            }
            if (generation != sessionGeneration || phase != Phase.DRAINING) return@launch
            generation += 1
            destroyTranscribers()
            lastTranscriptJob = null
            activeRecordingId = null
            recordingCreated = false
            phase = Phase.IDLE
            ServiceStateTracker.capturePhase.value = CapturePhase.IDLE
            if (restartRequested) {
                restartRequested = false
                startListening()
            } else {
                removeNotification()
                // Do not stop a newer start request that Android has queued but
                // has not delivered to onStartCommand yet.
                stopSelfResult(lastStartId)
            }
        }
    }

    private fun recordError(reason: String) {
        sessionErrors += reason
        ServiceStateTracker.lastTranscriptionError.value = sessionErrors.joinToString("\n")
    }

    private fun releaseAudioResources() {
        bluetoothAudioRouter?.release()
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        ServiceStateTracker.audioRoute.value = "Not listening"
        ServiceStateTracker.audioLevel.value = 0f
    }

    private fun destroyTranscribers() {
        val native = nemotronTranscriber
        val model = leasedModel
        val manager = (application as LiveNotesApplication).appContainer.modelDownloadManager
        nemotronTranscriber = null
        leasedModel = null
        if (native != null) {
            // Model loading and JNI shutdown are asynchronous. Keep its file
            // leased until the worker has actually released the native handle.
            native.destroy { if (model != null) manager.releaseFromCapture(model) }
        } else if (model != null) {
            manager.releaseFromCapture(model)
        }
        speechTranscriber?.destroy()
        speechTranscriber = null
    }

    override fun onDestroy() {
        phase = Phase.DESTROYED
        ServiceStateTracker.capturePhase.value = CapturePhase.IDLE
        generation += 1
        destroyTranscribers()
        releaseAudioResources()
        removeNotification()
        serviceScope.cancel()
        ServiceStateTracker.listening.value = false
        super.onDestroy()
    }

    private fun removeNotification() {
        notificationActive = false
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun updateNotification(content: String) {
        if (!notificationActive || phase == Phase.IDLE || phase == Phase.DESTROYED) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(getString(R.string.notification_listening_title), content))
    }

    private fun buildNotification(title: String, content: String): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, ForegroundListeningService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(title)
            .setContentText(content.ifBlank { "Listening in background" })
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .addAction(0, "Stop", stopIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_description)
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val manager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "livenotes:transcription").apply {
            acquire()
        }
    }

    companion object {
        private const val CHANNEL_ID = "live-notes-listening"
        private const val NOTIFICATION_ID = 42
        const val ACTION_START = "com.sainadh.livenotes.action.START"
        const val ACTION_STOP = "com.sainadh.livenotes.action.STOP"

        fun start(context: Context) {
            val intent = Intent(context, ForegroundListeningService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, ForegroundListeningService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }
    }
}
