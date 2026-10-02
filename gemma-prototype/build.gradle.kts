plugins { id("com.android.application") }

android {
    namespace = "com.sainadh.livenotes.gemmaprototype"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.sainadh.livenotes.gemmaprototype"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-gemma-test"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildTypes { getByName("debug") { isMinifyEnabled = false } }
    packaging { resources.excludes += setOf("META-INF/LICENSE*", "META-INF/NOTICE*") }
}

dependencies {
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.17.1")
    implementation("com.google.code.gson:gson:2.14.0")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
