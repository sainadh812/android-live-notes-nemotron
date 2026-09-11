package com.sainadh.livenotes.ui

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

/** Gradle pulls this directory before uninstalling the instrumented app. */
internal fun saveTestScreenshot(bitmap: Bitmap, name: String) {
    val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
        ?.let(::File) ?: InstrumentationRegistry.getInstrumentation().targetContext.filesDir
    check(output.isDirectory || output.mkdirs())
    File(output, name).outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
}
