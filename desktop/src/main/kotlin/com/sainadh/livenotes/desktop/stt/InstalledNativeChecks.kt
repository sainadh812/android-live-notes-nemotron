package com.sainadh.livenotes.desktop.stt

import java.io.File
import java.util.concurrent.TimeUnit

/** Used by the installed-launcher check, so development resource fallbacks cannot hide a packaging error. */
internal object InstalledNativeChecks {
    fun verify(evidence: File) {
        val resources = System.getProperty("compose.application.resources.dir")?.let(::File)
            ?: error("Installed launcher did not configure compose.application.resources.dir")
        check(resources.isDirectory) { "Packaged resources are missing: $resources" }
        val native = File(resources, "native")
        val report = File(evidence, "installed-native-checks.txt")
        report.writeText("resources_dir=${resources.canonicalPath}\n")
        for (name in listOf("ggml-base.dll", "ggml-cpu.dll", "ggml.dll", "transcribe.dll", "livenotes_jni.dll")) {
            val library = File(native, name)
            check(library.isFile && library.length() > 0) { "Packaged speech library is missing: $library" }
            report.appendText("present=$name\n")
        }
        // This runs in the smoke-only process; explicitly bind the actual installed
        // directory rather than accepting any repository-relative development path.
        System.setProperty("livenotes.native.dir", native.canonicalPath)
        NativeSpeech.ensureLoaded()
        report.appendText("native_dll_load=ok\n")

        val worker = File(resources, "speaker-worker/speaker-worker.exe")
        check(worker.isFile) { "Packaged speaker worker is missing: $worker" }
        val log = File(evidence, "installed-worker-self-test.log")
        val process = ProcessBuilder(worker.canonicalPath, "--self-test")
            .directory(evidence)
            .redirectErrorStream(true)
            .redirectOutput(log)
            .start()
        try {
            check(process.waitFor(30, TimeUnit.SECONDS)) { "Packaged speaker worker self-test timed out after 30 seconds" }
            report.appendText("speaker_worker_exit=${process.exitValue()}\n")
            check(process.exitValue() == 0) { "Packaged speaker worker self-test failed; inspect ${log.name}" }
            check(Regex("\"event\"\\s*:\\s*\"ready\"").containsMatchIn(log.readText())) {
                "Packaged speaker worker did not report ready; inspect ${log.name}"
            }
            report.appendText("speaker_worker_self_test=ok\n")
        } finally {
            if (process.isAlive) {
                process.descendants().use { children -> children.forEach { it.destroyForcibly() } }
                process.destroyForcibly()
                process.waitFor(5, TimeUnit.SECONDS)
            }
        }
    }
}
