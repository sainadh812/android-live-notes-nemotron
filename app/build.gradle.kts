import java.util.Properties
import java.security.MessageDigest

fun escapeBuildConfigString(value: String): String =
    value.replace("\\", "\\\\").replace("\"", "\\\"")

// Distributed APKs must never include credentials from the build machine.
val embeddedOpenAiKey = ""

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

val previewBuild = providers.gradleProperty("previewBuild").orNull == "true"
// Instrumented JVM/UI tests use x86 Android; published phone APKs always retain ARM JNI.
val emulatorTests = providers.gradleProperty("emulatorTests").orNull == "true"
val signingPropertiesFile = rootProject.file("signing.properties")
val releaseSigningProperties = Properties().apply {
    if (signingPropertiesFile.isFile) signingPropertiesFile.inputStream().use(::load)
}

android {
    namespace = "com.sainadh.livenotes"
    compileSdk = 36
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = if (emulatorTests) "com.sainadh.livenotes.emulatortest" else if (previewBuild) "com.sainadh.livenotes.preview" else "com.sainadh.livenotes"
        minSdk = 26
        targetSdk = 36
        versionCode = 9
        versionName = if (emulatorTests) "1.2.2-emulator-test" else if (previewBuild) "1.2.2-preview" else "1.2.2"
        manifestPlaceholders["appLabel"] = if (previewBuild) "LiveMeetingNotes Preview" else "@string/app_name"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        buildConfigField(
            "String",
            "EMBEDDED_OPENAI_API_KEY",
            "\"${escapeBuildConfigString(embeddedOpenAiKey)}\""
        )
        buildConfigField(
            "boolean",
            "HAS_EMBEDDED_OPENAI_API_KEY",
            if (embeddedOpenAiKey.isNotBlank()) "true" else "false"
        )

        // Production builds compile the complete pinned engine for every Android ABI.
        // Preview retains its existing phone binary; instrumentation uses separate test storage.
        ndk {
            abiFilters += when {
                emulatorTests -> listOf("x86_64")
                previewBuild -> listOf("arm64-v8a")
                else -> listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
            }
        }
    }

    if (emulatorTests) sourceSets.getByName("main").jniLibs.setSrcDirs(emptyList<String>())
    else if (!previewBuild) sourceSets.getByName("main").jniLibs.setSrcDirs(
        listOf(rootProject.file("build/native-android/jniLibs"))
    )

    signingConfigs {
        getByName("debug") {
            // CI's Android user directory can differ from the runner home directory.
            // Pin the original Preview key explicitly instead of letting AGP create a new one.
            providers.gradleProperty("developmentKeystore").orNull?.let {
                storeFile = rootProject.file(it)
            }
        }
        create("release") {
            storeFile = releaseSigningProperties.getProperty("storeFile")?.let { rootProject.file(it) }
            storePassword = releaseSigningProperties.getProperty("storePassword")
            keyAlias = releaseSigningProperties.getProperty("keyAlias")
            keyPassword = releaseSigningProperties.getProperty("keyPassword")
        }
    }

    buildTypes {
        debug {
            // Instrumentation loads code from a separate APK. Keep shared Kotlin
            // helpers that are used only by its runner, outside the app's call graph.
            isMinifyEnabled = !emulatorTests
            isShrinkResources = !emulatorTests
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            ndk.debugSymbolLevel = "FULL"
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

val verifyReleaseConfiguration by tasks.registering {
    group = "verification"
    description = "Requires production app settings and a private upload key for release builds."
    doLast {
        check(!previewBuild && !emulatorTests) {
            "Release builds require the production package. Remove -PpreviewBuild and -PemulatorTests."
        }
        check(signingPropertiesFile.isFile) {
            "Release signing is not configured. Copy signing.properties.example to signing.properties and configure your private upload key."
        }
        check(listOf("storeFile", "storePassword", "keyAlias", "keyPassword").all {
            !releaseSigningProperties.getProperty(it).isNullOrBlank()
        }) {
            "signing.properties must contain storeFile, storePassword, keyAlias, and keyPassword."
        }
        check(rootProject.file(releaseSigningProperties.getProperty("storeFile")).isFile) {
            "The upload keystore specified by storeFile in signing.properties does not exist."
        }
    }
}

tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    dependsOn(verifyReleaseConfiguration)
}

val verifyNativeJni by tasks.registering {
    group = "verification"
    description = "Rejects stale JNI binaries; rebuild them with scripts/build-native-jni.sh."
    val source = layout.projectDirectory.file("src/main/cpp/nemotron_jni.cpp")
    val binary = layout.projectDirectory.file("src/main/jniLibs/arm64-v8a/libnemotron_jni.so")
    val metadata = layout.projectDirectory.file("src/main/jniLibs/nemotron-jni-build.properties")
    inputs.files(source, binary, metadata)
    doLast {
        val properties = Properties().apply { metadata.asFile.inputStream().use(::load) }
        fun sha256(file: java.io.File): String =
            MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        check(properties.getProperty("sourceSha256") == sha256(source.asFile)) {
            "JNI source changed without rebuilding its binary. Run scripts/build-native-jni.sh."
        }
        check(properties.getProperty("binarySha256") == sha256(binary.asFile)) {
            "JNI binary does not match its build record. Run scripts/build-native-jni.sh."
        }
    }
}

if (previewBuild || emulatorTests) tasks.named("preBuild").configure { dependsOn(verifyNativeJni) }

if (!previewBuild && !emulatorTests) {
    val buildAndroidNative by tasks.registering(Exec::class) {
        group = "build"
        description = "Builds all four Android native ABIs from the pinned engine and JNI sources."
        inputs.dir(layout.projectDirectory.dir("src/main/cpp"))
        inputs.files(rootProject.file("scripts/build-native-android.sh"),
            rootProject.file("scripts/check-native-page-alignment.py"))
        inputs.dir(rootProject.file("scripts/native-patches"))
        inputs.property("ndkVersion", "27.2.12479018")
        outputs.dir(rootProject.layout.buildDirectory.dir("native-android/jniLibs"))
        workingDir(rootProject.projectDir)
        environment("ANDROID_NDK_HOME", android.sdkDirectory.resolve("ndk/27.2.12479018").absolutePath)
        environment("NEMOTRON_ANDROID_BUILD_DIR", rootProject.layout.buildDirectory.dir("native-android").get().asFile.absolutePath)
        commandLine("bash", rootProject.file("scripts/build-native-android.sh").absolutePath,
            "arm64-v8a", "armeabi-v7a", "x86", "x86_64")
    }
    tasks.named("preBuild").configure { dependsOn(buildAndroidNative) }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")

    implementation("com.android.billingclient:billing:9.1.0")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.core:core-splashscreen:1.0.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
