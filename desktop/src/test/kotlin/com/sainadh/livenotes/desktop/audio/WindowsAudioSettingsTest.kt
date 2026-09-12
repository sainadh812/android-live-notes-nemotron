package com.sainadh.livenotes.desktop.audio

import org.junit.Assert.*
import org.junit.Test

class WindowsAudioSettingsTest {
    @Test fun desktopAllowanceTakesPriorityOverUwpOnlyDenial() {
        assertEquals("allowed", WindowsAudioSettings.permissionView("Allow", "Deny", "Allow").status)
    }
    @Test fun deviceOrDesktopDenialIsReportedAndUnknownRemainsUnknown() {
        assertEquals("denied", WindowsAudioSettings.permissionView("Deny", "Allow", "Allow").status)
        assertEquals("denied", WindowsAudioSettings.permissionView("Allow", "Allow", "Deny").status)
        assertEquals("unknown", WindowsAudioSettings.permissionView(null, null, null).status)
    }
}
