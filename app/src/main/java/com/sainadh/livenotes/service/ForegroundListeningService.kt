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
import com.sainadh.livenotes.stt.ModelDownloadManager
import com.sainadh.livenotes.stt.NemotronTranscriber
import com.sainadh.livenotes.stt.SpeechTranscriber
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

object ServiceStateTracker {
    val listening = MutableStateFlow(false)
    val latestTranscript = MutableStateFlow("")
    val audioRoute = MutableStateFlow("Not listening")
    val lastSummaryError = MutableStateFlow<String?>(null)
}

class ForegroundListeningService : Service(), SpeechTranscriber.Listener {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var speechTranscriber: SpeechTranscriber? = null
    private var nemotronTranscriber: NemotronTranscriber? = null
    private var usingNemotron = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var bluetoothAudioRouter: BluetoothAudioRouter? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        bluetoothAudioRouter = BluetoothAudioRouter(this)

        // Picks whichever quant is actually present on disk (the user may
        // have downloaded any of the four via the in-app model downloader -
        // see ModelDownloadManager / the "On-device model" settings section).
        val downloadManager = ModelDownloadManager(this)
        val availableQuant = downloadManager.findAnyDownloaded()
        usingNemotron = availableQuant != null
        if (usingNemotron && availableQuant != null) {
            nemotronTranscriber = NemotronTranscriber(
                context = this,
                modelPath = downloadManager.modelFile(availableQuant).absolutePath,
                language = "en-US",
                listener = this
            )
        } else {
            speechTranscriber = SpeechTranscriber(this, this)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopListeningAndSelf()
            else -> startListening()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startListening() {
        startForeground(
            NOTIFICATION_ID,
            buildNotification(getString(R.string.notification_listening_title), "Preparing microphone")
        )
        ServiceStateTracker.listening.value = true
        ServiceStateTracker.lastSummaryError.value = null
        val app = application as LiveNotesApplication
        val selectedInputMode = app.appContainer.secureSettings.readAudioInputMode()
        val resolvedRoute = bluetoothAudioRouter?.activate(selectedInputMode) ?: "Phone microphone"
        ServiceStateTracker.audioRoute.value = resolvedRoute
        acquireWakeLock()
        if (usingNemotron) nemotronTranscriber?.start() else speechTranscriber?.start()
        updateNotification("Using $resolvedRoute")
    }

    private fun stopListeningAndSelf() {
        ServiceStateTracker.listening.value = false
        ServiceStateTracker.latestTranscript.value = ""
        ServiceStateTracker.audioRoute.value = "Not listening"
        if (usingNemotron) nemotronTranscriber?.stop() else speechTranscriber?.stop()
        bluetoothAudioRouter?.release()
        wakeLock?.takeIf { it.isHeld }?.release()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (usingNemotron) nemotronTranscriber?.destroy() else speechTranscriber?.destroy()
        bluetoothAudioRouter?.release()
        wakeLock?.takeIf { it.isHeld }?.release()
        serviceScope.cancel()
        ServiceStateTracker.listening.value = false
        ServiceStateTracker.audioRoute.value = "Not listening"
        super.onDestroy()
    }

    override fun onTranscript(text: String, isFinal: Boolean) {
        ServiceStateTracker.latestTranscript.value = text
        serviceScope.launch {
            val app = application as LiveNotesApplication
            val result = app.appContainer.conversationOrchestrator.onTranscript(text, isFinal)
            result.fold(
                onSuccess = { ServiceStateTracker.lastSummaryError.value = null },
                onFailure = { error ->
                    val message = error.message ?: error.javaClass.simpleName
                    ServiceStateTracker.lastSummaryError.value = message
                    updateNotification("Summary error: $message")
                }
            )
        }
        updateNotification(text)
    }

    override fun onStateChanged(state: String) {
        updateNotification(state)
    }

    override fun onError(reason: String) {
        updateNotification(reason)
    }

    private fun updateNotification(content: String) {
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
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
