package com.sainadh.livenotes.desktop.data

import com.sainadh.livenotes.desktop.AppSettings
import com.sun.jna.platform.win32.Crypt32Util
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal fun atomicWrite(file: File, bytes: ByteArray) {
    val temporary = File(file.parentFile, "${file.name}.tmp")
    java.io.FileOutputStream(temporary).use { it.write(bytes); it.fd.sync() }
    try { Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
    catch (_: java.nio.file.AtomicMoveNotSupportedException) {
        Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}

class SettingsStore(root: File) {
    private val file = File(root, "settings.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    fun load(): AppSettings = if (file.isFile) json.decodeFromString(file.readText()) else AppSettings()
    fun save(settings: AppSettings) = atomicWrite(file, json.encodeToString(settings).toByteArray())
}

interface SecretStore {
    fun read(provider: String): String
    fun save(provider: String, key: String)
    fun delete(provider: String)
}

/** Windows DPAPI binds encrypted API keys to the current Windows user. */
class WindowsSecretStore(private val root: File) : SecretStore {
    private fun path(provider: String): File {
        require(provider in setOf("OPENAI", "DEEPSEEK", "QWEN"))
        return File(root, "$provider.key")
    }
    override fun read(provider: String): String {
        val file = path(provider)
        if (!file.isFile) return ""
        check(System.getProperty("os.name").startsWith("Windows")) { "Saved API keys use Windows account encryption." }
        return Crypt32Util.cryptUnprotectData(file.readBytes()).toString(Charsets.UTF_8)
    }
    override fun save(provider: String, key: String) {
        require(key.isNotBlank()) { "Enter an API key first." }
        check(System.getProperty("os.name").startsWith("Windows")) { "Saving API keys requires Windows account encryption." }
        atomicWrite(path(provider), Crypt32Util.cryptProtectData(key.trim().toByteArray()))
    }
    override fun delete(provider: String) { Files.deleteIfExists(path(provider).toPath()) }
}
