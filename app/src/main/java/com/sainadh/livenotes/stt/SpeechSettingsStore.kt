package com.sainadh.livenotes.stt

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Speech choices are separate from cloud-summary credentials. Null model means Android speech. */
class SpeechSettingsStore(context: Context, legacyModel: SpeechModel?) {
    private val prefs = context.getSharedPreferences("speech-settings", Context.MODE_PRIVATE)
    private val initialModel = if (prefs.contains("model")) {
        SpeechModel.fromId(prefs.getString("model", null))
    } else legacyModel
    private val initialLanguage = SpeechLanguage.fromCode(prefs.getString("language", null))
    private val current = MutableStateFlow(SpeechSettings(initialModel, validLanguage(initialModel, initialLanguage)))
    val state = current.asStateFlow()

    init {
        // Freeze the migrated choice, so future downloads never silently select a different engine.
        if (!prefs.contains("model")) persist(current.value)
    }

    fun selectModel(model: SpeechModel?) {
        update(SpeechSettings(model, validLanguage(model, current.value.language)))
    }

    fun selectLanguage(language: SpeechLanguage) {
        update(current.value.copy(language = validLanguage(current.value.model, language)))
    }

    private fun update(settings: SpeechSettings) {
        persist(settings)
        current.value = settings
    }

    private fun persist(settings: SpeechSettings) {
        prefs.edit().putString("model", settings.model?.id ?: "android")
            .putString("language", settings.language.code).apply()
    }

    private fun validLanguage(model: SpeechModel?, language: SpeechLanguage): SpeechLanguage =
        if (model == null) {
            // Android language auto-detection is provider-dependent; keep this mode explicitly English.
            SpeechLanguage.ENGLISH
        } else if (language in model.languages) language else SpeechLanguage.ENGLISH
}
