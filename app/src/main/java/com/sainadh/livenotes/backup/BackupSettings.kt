package com.sainadh.livenotes.backup

import com.sainadh.livenotes.ai.LlmProvider
import com.sainadh.livenotes.audio.AudioInputMode
import com.sainadh.livenotes.stt.SpeechLanguage
import com.sainadh.livenotes.stt.SpeechModel
import kotlinx.serialization.Serializable

/** Logical values only. Android Keystore keys and encrypted preference files never leave the app. */
@Serializable
data class SecureSettingsSnapshot(
    val apiKey: String, val provider: String, val model: String, val audioInputMode: String
) {
    fun validate() {
        require(apiKey.length <= 32_768 && model.length <= 1_024) { "Backup settings exceed supported limits." }
        require(LlmProvider.entries.any { it.name == provider }) { "Unsupported AI provider in backup." }
        require(AudioInputMode.entries.any { it.name == audioInputMode }) { "Unsupported audio setting in backup." }
    }
    override fun toString(): String = "SecureSettingsSnapshot(credentials=redacted)"
}

@Serializable
data class SpeechSettingsSnapshot(val modelId: String, val languageCode: String) {
    fun validate() {
        val model = SpeechModel.fromId(modelId)
        require(modelId == "android" || model != null) { "Unsupported speech model in backup." }
        val language = SpeechLanguage.entries.firstOrNull { it.code == languageCode }
        require(language != null && if (model == null) language == SpeechLanguage.ENGLISH else language in model.languages) {
            "Unsupported speech language in backup."
        }
    }
}

@Serializable
data class BackupSettings(val secure: SecureSettingsSnapshot, val speech: SpeechSettingsSnapshot) {
    fun validate() { secure.validate(); speech.validate() }
    override fun toString(): String = "BackupSettings(credentials=redacted)"
}
