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
import com.sainadh.livenotes.stt.SpeechTranscriber
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object ServiceStateTracker {
    val listening = MutableStateFlow(false)
    val latestTranscript = MutableStateFlow("")
    val audioRoute = MutableStateFlow("Not listening")
    val lastTranscriptionError = MutableStateFlow<String?>(null)
}

class ForegroundListeningService : Service() {
    private enum class Phase { IDLE, LISTENING, STOPPING, DRAINING, DESTROYED }

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
    private var wakeLock: PowerManager.WakeLock? = null
    private var bluetoothAudioRouter: BluetoothAudioRouter? = null

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

    private fun resolveTranscriber(listener: SpeechTranscriber.Listener) {
        val app = application as LiveNotesApplication
        val downloadManager = app.appContainer.modelDownloadManager
        val availableQuant = downloadManager.findAnyDownloaded()
        usingNemotron = availableQuant != null && NemotronTranscriber.isAvailable()
        if (usingNemotron && availableQuant != null) {
            nemotronTranscriber = NemotronTranscriber(
                context = this,
                modelPath = downloadManager.modelFile(availableQuant).absolutePath,
                language = "en-US",
                listener = listener
            )
        } else {
            if (availableQuant != null && !NemotronTranscriber.isAvailable()) {
                ServiceStateTracker.lastTranscriptionError.value =
                    "On-device libraries could not load (${NemotronTranscriber.loadError()}). Using the OS speech recognizer."
            }
            speechTranscriber = SpeechTranscriber(this, listener)
        }
    }

    private fun startListening() {
        when (phase) {
            Phase.LISTENING, Phase.DESTROYED -> return
            Phase.STOPPING, Phase.DRAINING -> {
                restartRequested = true
                return
            }
            Phase.IDLE -> Unit
        }
        phase = Phase.LISTENING
        val sessionGeneration = ++generation
        ServiceStateTracker.lastTranscriptionError.value = null
        ServiceStateTracker.latestTranscript.value = ""
        try {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(getString(R.string.notification_listening_title), "Preparing microphone")
            )
            notificationActive = true
            resolveTranscriber(callbacks(sessionGeneration))
            val app = application as LiveNotesApplication
            val inputMode = app.appContainer.secureSettings.readAudioInputMode()
            val route = bluetoothAudioRouter?.activate(inputMode) ?: "Phone microphone"
            ServiceStateTracker.audioRoute.value = route
            ServiceStateTracker.listening.value = true
            acquireWakeLock()
            updateNotification(
                "Using $route" + if (usingNemotron) " (on-device Nemotron)" else " (OS speech recognizer)"
            )
            if (usingNemotron) nemotronTranscriber?.start() else speechTranscriber?.start()
        } catch (error: RuntimeException) {
            ServiceStateTracker.lastTranscriptionError.value = error.message ?: "Could not start microphone capture"
            finishCapture(sessionGeneration)
        }
    }

    private fun callbacks(sessionGeneration: Int) = object : SpeechTranscriber.Listener {
        private fun acceptsCallbacks() = generation == sessionGeneration &&
            (phase == Phase.LISTENING || phase == Phase.STOPPING)

        override fun onTranscript(text: String, isFinal: Boolean) {
            if (!acceptsCallbacks()) return
            ServiceStateTracker.latestTranscript.value = text
            val timestampMs = System.currentTimeMillis()
            val previous = lastTranscriptJob
            lastTranscriptJob = serviceScope.launch {
                previous?.join()
                try {
                    val app = application as LiveNotesApplication
                    val result = withContext(Dispatchers.IO) {
                        app.appContainer.conversationOrchestrator.onTranscript(text, isFinal, timestampMs)
                    }
                    result.exceptionOrNull()?.let {
                        ServiceStateTracker.lastTranscriptionError.value =
                            "Could not save transcript: ${it.message ?: it.javaClass.simpleName}"
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    ServiceStateTracker.lastTranscriptionError.value =
                        "Could not save transcript: ${error.message ?: error.javaClass.simpleName}"
                }
            }
            if (phase == Phase.LISTENING) updateNotification(text)
        }

        override fun onStateChanged(state: String) {
            if (!acceptsCallbacks()) return
            if (state == "stopped") {
                finishCapture(sessionGeneration)
            } else if (phase == Phase.LISTENING) {
                if (state == "ready") ServiceStateTracker.lastTranscriptionError.value = null
                updateNotification(state)
            }
        }

        override fun onError(reason: String) {
            if (!acceptsCallbacks()) return
            ServiceStateTracker.lastTranscriptionError.value = reason
            updateNotification(reason)
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
            Phase.LISTENING -> {
                phase = Phase.STOPPING
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
        ServiceStateTracker.listening.value = false
        releaseAudioResources()
        updateNotification("Saving transcript")
        val finalWrite = lastTranscriptJob
        serviceScope.launch {
            finalWrite?.join()
            if (generation != sessionGeneration || phase != Phase.DRAINING) return@launch
            generation += 1
            destroyTranscribers()
            lastTranscriptJob = null
            phase = Phase.IDLE
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

    private fun releaseAudioResources() {
        bluetoothAudioRouter?.release()
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        ServiceStateTracker.audioRoute.value = "Not listening"
    }

    private fun destroyTranscribers() {
        nemotronTranscriber?.destroy()
        nemotronTranscriber = null
        speechTranscriber?.destroy()
        speechTranscriber = null
    }

    override fun onDestroy() {
        phase = Phase.DESTROYED
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
