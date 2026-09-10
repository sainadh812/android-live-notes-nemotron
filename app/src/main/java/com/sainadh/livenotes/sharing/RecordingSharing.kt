package com.sainadh.livenotes.sharing

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import com.sainadh.livenotes.audio.recordingAudioFile
import com.sainadh.livenotes.data.SavedRecording

object RecordingSharing {
    fun copyText(context: Context, text: String, label: String) = reportFailure(context) {
        if (text.isBlank()) return@reportFailure
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText(label, text))
        // Android 13+ provides its own clipboard confirmation.
        if (android.os.Build.VERSION.SDK_INT < 33) Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    fun shareText(context: Context, text: String, title: String) = reportFailure(context) {
        if (text.isBlank()) return@reportFailure
        val intent = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, title).putExtra(Intent.EXTRA_TEXT, text)
        context.startActivity(Intent.createChooser(intent, "Share transcript"))
    }

    fun shareAudio(context: Context, recording: SavedRecording) = reportFailure(context) {
        val file = recordingAudioFile(context, recording.audioFileName)
        check(file.isFile) { "The audio file is no longer available." }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.recordings", file)
        val intent = Intent(Intent.ACTION_SEND).setType("audio/wav")
            .putExtra(Intent.EXTRA_STREAM, uri).putExtra(Intent.EXTRA_SUBJECT, recording.title)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        intent.clipData = ClipData.newUri(context.contentResolver, recording.title, uri)
        context.startActivity(Intent.createChooser(intent, "Share recording"))
    }

    private inline fun reportFailure(context: Context, action: () -> Unit) {
        try { action() } catch (error: Exception) {
            Toast.makeText(context, error.message ?: "Could not share this recording", Toast.LENGTH_LONG).show()
        }
    }
}
