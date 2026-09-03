package com.sainadh.livenotes.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.sainadh.livenotes.BuildConfig
import com.sainadh.livenotes.ai.LlmProvider
import com.sainadh.livenotes.audio.AudioInputMode

class ApiKeyStore(context: Context) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "secure-settings",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun saveApiKey(apiKey: String) {
        prefs.edit().putString(KEY_API, apiKey.trim()).apply()
    }

    fun readApiKey(): String {
        val manualKey = prefs.getString(KEY_API, "").orEmpty().trim()
        if (manualKey.isNotBlank()) return manualKey
        return BuildConfig.EMBEDDED_OPENAI_API_KEY.trim()
    }

    fun saveProvider(provider: LlmProvider) {
        prefs.edit().putString(KEY_PROVIDER, provider.name).apply()
    }

    fun readProvider(): LlmProvider {
        val raw = prefs.getString(KEY_PROVIDER, LlmProvider.OPENAI.name).orEmpty()
        return LlmProvider.entries.firstOrNull { it.name == raw } ?: LlmProvider.OPENAI
    }

    fun saveModel(model: String) {
        prefs.edit().putString(KEY_MODEL, model.trim()).apply()
    }

    fun readModel(provider: LlmProvider = readProvider()): String {
        val raw = prefs.getString(KEY_MODEL, provider.defaultModel).orEmpty().trim()
        return raw.ifBlank { provider.defaultModel }
    }

    fun saveAudioInputMode(mode: AudioInputMode) {
        prefs.edit().putString(KEY_AUDIO_INPUT_MODE, mode.name).apply()
    }

    fun readAudioInputMode(): AudioInputMode {
        val raw = prefs.getString(KEY_AUDIO_INPUT_MODE, AudioInputMode.AUTO.name).orEmpty()
        return AudioInputMode.entries.firstOrNull { it.name == raw } ?: AudioInputMode.AUTO
    }

    companion object {
        private const val KEY_API = "api_key"
        private const val KEY_PROVIDER = "provider"
        private const val KEY_MODEL = "model"
        private const val KEY_AUDIO_INPUT_MODE = "audio_input_mode"
    }
}
