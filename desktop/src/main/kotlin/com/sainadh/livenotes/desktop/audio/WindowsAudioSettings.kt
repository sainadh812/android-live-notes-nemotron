package com.sainadh.livenotes.desktop.audio

import com.sainadh.livenotes.desktop.MicrophoneAccessView
import com.sun.jna.Platform
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.Shell32
import com.sun.jna.platform.win32.WinReg
import com.sun.jna.platform.win32.WinUser

/** Advisory checks only. Windows and the device driver decide whether capture can open. */
object WindowsAudioSettings {
    private const val MICROPHONE = "Software\\Microsoft\\Windows\\CurrentVersion\\CapabilityAccessManager\\ConsentStore\\microphone"

    fun microphoneAccess(): MicrophoneAccessView {
        if (!Platform.isWindows()) return MicrophoneAccessView()
        fun read(root: WinReg.HKEY, path: String): String? = runCatching {
            Advapi32Util.registryGetStringValue(root, path, "Value")
        }.getOrNull()
        return permissionView(read(WinReg.HKEY_LOCAL_MACHINE, MICROPHONE),
            read(WinReg.HKEY_CURRENT_USER, MICROPHONE), read(WinReg.HKEY_CURRENT_USER, "$MICROPHONE\\NonPackaged"))
    }

    internal fun permissionView(device: String?, apps: String?, desktop: String?): MicrophoneAccessView {
        // NonPackaged governs desktop applications. A UWP-only switch does not
        // override an explicit allowance for desktop apps.
        val status = when {
            device.equals("deny", true) || desktop.equals("deny", true) -> "denied"
            desktop.equals("allow", true) -> "allowed"
            apps.equals("deny", true) -> "denied"
            device.equals("allow", true) && apps.equals("allow", true) -> "allowed"
            else -> "unknown"
        }
        val message = when (status) {
            "denied" -> "Windows reports microphone access is off. Check microphone access and access for desktop apps in Windows Settings."
            "allowed" -> "Windows reports microphone access is enabled. Test your microphone to check the selected device."
            else -> "Windows microphone permission could not be confirmed. Test your microphone or check access for desktop apps in Windows Settings."
        }
        return MicrophoneAccessView(true, status, message)
    }

    fun openMicrophone() = open("ms-settings:privacy-microphone")
    fun openSound() = open("ms-settings:sound")
    private fun open(uri: String) {
        check(Platform.isWindows()) { "Open your operating system's sound settings to manage audio devices." }
        val result = Shell32.INSTANCE.ShellExecute(null, "open", uri, null, null, WinUser.SW_SHOWNORMAL)
        check(result.toLong() > 32) { "Windows Settings could not open. Open Settings → Privacy & security → Microphone, or System → Sound." }
    }
}
