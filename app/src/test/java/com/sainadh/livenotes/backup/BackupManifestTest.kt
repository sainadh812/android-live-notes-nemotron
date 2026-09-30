package com.sainadh.livenotes.backup

import com.sainadh.livenotes.data.LibrarySnapshot
import com.sainadh.livenotes.stt.SpeechModel
import org.junit.Assert.assertThrows
import org.junit.Test

class BackupManifestTest {
    @Test fun whitelistRejectsTraversalUnknownModelsAndAbsolutePaths() {
        listOf("../secret", "/recordings/note.wav", "recordings/../note.wav", "recordings/a/b.wav",
            "recordings/a\\b.wav", "recordings/x.wav/../../secret", "models/unknown.gguf", "secure-settings.xml",
            "recordings/x.wav.words.words").forEach { path ->
            assertThrows(Exception::class.java) { LocalBackupManager.validateArchivePath(path) }
        }
        LocalBackupManager.validateArchivePath("recordings/meeting.wav")
        LocalBackupManager.validateArchivePath("recordings/meeting.wav.words")
        LocalBackupManager.validateArchivePath("models/${SpeechModel.MOONSHINE_TINY.fileName}")
    }

    @Test fun modelManifestCannotSubstituteBytesOrDigestOrDuplicatePaths() {
        val model = SpeechModel.MOONSHINE_TINY
        val entry = BackupFile("models/${model.fileName}", model.expectedBytes, model.sha256)
        LocalBackupManager.validateManifest(manifest(listOf(entry)))
        listOf(entry.copy(bytes = 10), entry.copy(sha256 = "a".repeat(64))).forEach {
            assertThrows(IllegalArgumentException::class.java) { LocalBackupManager.validateManifest(manifest(listOf(it))) }
        }
        assertThrows(IllegalArgumentException::class.java) { LocalBackupManager.validateManifest(manifest(listOf(entry, entry))) }
        assertThrows(IllegalArgumentException::class.java) { LocalBackupManager.validateManifest(manifest(listOf(entry)).copy(includesModels = false)) }
    }

    private fun manifest(files: List<BackupFile>) = BackupManifest(createdAtEpochMs = 1, includesModels = true,
        library = LibrarySnapshot(), settings = BackupSettings(
            SecureSettingsSnapshot("", "OPENAI", "model", "AUTO"), SpeechSettingsSnapshot("android", "en-US")), files = files)
}
