package com.sainadh.livenotes.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build

class BluetoothAudioRouter(context: Context) {
    private val audioManager = context.getSystemService(AudioManager::class.java)

    fun activate(inputMode: AudioInputMode): String {
        return when (inputMode) {
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
    }

    fun release() {
        activatePhoneMicrophone()
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
