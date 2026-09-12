import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "1.9.24"
    kotlin("plugin.serialization") version "1.9.24"
    id("org.jetbrains.compose") version "1.6.11"
}

group = "com.sainadh.livenotes"
version = "1.0.1"
kotlin { jvmToolchain(17) }

// Compile the same platform-independent source used by Android. Generated copies
// live under build/; there is only one maintained implementation of this logic.
val sharedSources by tasks.registering(Sync::class) {
    from("../app/src/main/java") {
        include("com/sainadh/livenotes/stt/TranscriptUpdate.kt")
        include("com/sainadh/livenotes/stt/TranscriptSampleClock.kt")
        include("com/sainadh/livenotes/stt/NativeTranscriptSegments.kt")
        include("com/sainadh/livenotes/stt/LiveTranscriptBuffer.kt")
        include("com/sainadh/livenotes/stt/NativeWordTimingFile.kt")
        include("com/sainadh/livenotes/stt/SpeechModel.kt")
        include("com/sainadh/livenotes/audio/WavFileWriter.kt")
        include("com/sainadh/livenotes/data/RecordingTimeline.kt")
        include("com/sainadh/livenotes/ai/ChatCompletionClient.kt")
        include("com/sainadh/livenotes/ai/SummaryScheduler.kt")
    }
    into(layout.buildDirectory.dir("generated/shared"))
}
kotlin.sourceSets.main { kotlin.srcDir(sharedSources) }

// Ship the model agreements even when a user downloads only the GGUF file.
val modelNotices by tasks.registering(Sync::class) {
    from("model-mirror") { include("Notice.txt", "licenses/**", "manifest.json") }
    into(layout.projectDirectory.dir("resources/common/model-notices"))
}
tasks.matching { it.name in setOf("prepareAppResources", "run", "createDistributable", "packageExe", "packageMsi") }
    .configureEach { dependsOn(modelNotices) }

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.xerial:sqlite-jdbc:3.46.1.0")
    implementation("net.java.dev.jna:jna-platform:5.14.0")
    implementation("org.slf4j:slf4j-nop:2.0.13")
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation(compose.desktop.uiTestJUnit4)
}

tasks.test { useJUnit(); systemProperty("java.awt.headless", "true") }

tasks.register<JavaExec>("speechSmoke") {
    dependsOn(tasks.classes)
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.sainadh.livenotes.desktop.stt.SpeechFileCli")
    systemProperty("livenotes.native.dir", project.file("resources/windows-x64/native").absolutePath)
    doFirst {
        val fixtures = providers.gradleProperty("speechFixtures").orNull
        if (fixtures != null) args("--fixtures", fixtures)
        else args(providers.gradleProperty("speechModel").get(), providers.gradleProperty("speechWav").get())
    }
}

tasks.register<JavaExec>("speakerCancellationCheck") {
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("com.sainadh.livenotes.desktop.speakers.WorkerCancellationCheck")
    args(project.file("resources/windows-x64/speaker-worker").absolutePath,
        project.file("build/speaker-evidence").absolutePath,
        project.file("build/speaker-evidence/0-four-speakers-zh.wav").absolutePath,
        project.file("build/speaker-evidence/cancellation").absolutePath)
}

tasks.register<JavaExec>("modelTransferCheck") {
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("com.sainadh.livenotes.desktop.data.ModelTransferCheck")
    systemProperty("livenotes.native.dir", project.file("resources/windows-x64/native").absolutePath)
    args(project.file("build/speech-evidence").absolutePath)
}

compose.desktop {
    application {
        mainClass = "com.sainadh.livenotes.desktop.MainKt"
        jvmArgs += listOf("-Xmx2048m", "-Dfile.encoding=UTF-8")
        nativeDistributions {
            targetFormats(TargetFormat.Exe, TargetFormat.Msi)
            packageName = "LiveMeetingNotes"
            packageVersion = "1.0.1"
            description = "Local meeting recording, transcription and speaker notes"
            vendor = "LiveMeetingNotes"
            modules("java.sql", "java.desktop", "java.net.http", "jdk.crypto.ec", "jdk.unsupported", "java.naming")
            appResourcesRootDir.set(project.layout.projectDirectory.dir("resources"))
            windows {
                menuGroup = "LiveMeetingNotes"
                shortcut = true
                dirChooser = true
                perUserInstall = true
                upgradeUuid = "c24a442c-028e-43cf-aa78-229e91c87c32"
            }
        }
    }
}
