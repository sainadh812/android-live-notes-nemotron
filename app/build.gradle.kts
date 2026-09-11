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

android {
    namespace = "com.sainadh.livenotes"
    compileSdk = 35
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = if (emulatorTests) "com.sainadh.livenotes.emulatortest" else if (previewBuild) "com.sainadh.livenotes.preview" else "com.sainadh.livenotes"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = if (emulatorTests) "1.1.0-emulator-test" else if (previewBuild) "1.1.0-preview" else "1.1.0"
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

        // Prebuilt transcribe.cpp / Nemotron JNI libs currently only exist
        // for arm64-v8a (see app/src/main/jniLibs/arm64-v8a). Restrict here
        // so the build doesn't try to package other ABIs it has no .so for.
        ndk {
            abiFilters += if (emulatorTests) "x86_64" else "arm64-v8a"
        }
    }

    if (emulatorTests) sourceSets.getByName("main").jniLibs.setSrcDirs(emptyList<String>())

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
            signingConfig = signingConfigs.getByName("debug")
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

tasks.named("preBuild").configure { dependsOn(verifyNativeJni) }

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")

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
