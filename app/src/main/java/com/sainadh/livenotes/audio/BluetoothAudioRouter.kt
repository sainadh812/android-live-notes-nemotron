package com.sainadh.livenotes.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build

class BluetoothAudioRouter(context: Context) {
    private val audioManager = context.getSystemService(AudioManager::class.java)

    /**
     * Attempts audio routing for [inputMode]. Any SecurityException here
     * (audio-routing calls like setMode/setCommunicationDevice/
     * startBluetoothSco require MODIFY_AUDIO_SETTINGS, which is now
     * declared in the manifest - but this is still called unconditionally
     * from ForegroundListeningService.startListening() on the main thread
     * with no caller-side try/catch, so a defensive catch here prevents an
     * OEM-specific audio-stack quirk from crashing the whole app process
     * before the transcriber even starts) degrades to the phone mic
     * instead of propagating.
     */
    fun activate(inputMode: AudioInputMode): String {
        return try {
            when (inputMode) {
                AudioInputMode.AUTO -> {
                    if (tryActivateBluetoothRoute()) {
                        "Bluetooth microphone"
                    } else {
                        activatePhoneMicrophone()
                        "Phone microphone"
                    }
                }
                AudioInputMode.PHONE_MIC -> {
                    activatePhoneMicrophone()
                    "Phone microphone"
                }
                AudioInputMode.BLUETOOTH_MIC -> {
                    if (tryActivateBluetoothRoute()) {
                        "Bluetooth microphone"
                    } else {
                        activatePhoneMicrophone()
                        "Phone microphone (Bluetooth unavailable)"
                    }
                }
            }
        } catch (e: SecurityException) {
            "Phone microphone (audio routing permission denied: ${e.message})"
        }
    }

    fun release() {
        try {
            activatePhoneMicrophone()
        } catch (_: SecurityException) {
            // Best-effort cleanup; nothing more to do if this fails too.
        }
    }

    private fun tryActivateBluetoothRoute(): Boolean {
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val device = audioManager.availableCommunicationDevices.firstOrNull { candidate ->
                candidate.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    candidate.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                    candidate.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
            } ?: return false
            audioManager.setCommunicationDevice(device)
        } else {
            val hasBluetoothAudioDevice = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .any { candidate ->
                    candidate.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                        candidate.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                }
            if (!hasBluetoothAudioDevice) return false
            @Suppress("DEPRECATION")
            audioManager.startBluetoothSco()
            @Suppress("DEPRECATION")
            audioManager.isBluetoothScoOn = true
            true
        }
    }

    private fun activatePhoneMicrophone() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.clearCommunicationDevice()
        } else {
            @Suppress("DEPRECATION")
            audioManager.stopBluetoothSco()
            @Suppress("DEPRECATION")
            audioManager.isBluetoothScoOn = false
        }
        audioManager.mode = AudioManager.MODE_NORMAL
    }
}
