package com.sainadh.livenotes.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.sainadh.livenotes.desktop.data.AppPaths
import com.sainadh.livenotes.desktop.data.SingleInstance
import com.sainadh.livenotes.desktop.stt.InstalledNativeChecks
import com.sainadh.livenotes.desktop.ui.DesktopApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.awt.Dimension
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.Robot
import java.awt.Toolkit
import java.io.File
import javax.imageio.ImageIO
import javax.swing.JOptionPane

fun main(args: Array<String>) {
    val smoke = args.indexOf("--smoke-test").takeIf { it >= 0 }?.let { index ->
        require(index + 1 < args.size) { "--smoke-test requires an evidence directory" }
        File(args[index + 1]).absoluteFile.apply { mkdirs() }
    }
    if (smoke != null) System.setProperty("livenotes.dataDir", File(smoke, "test-library").absolutePath)
    val paths: AppPaths
    val instance: SingleInstance
    val controller: DesktopController
    try {
        paths = AppPaths()
        instance = SingleInstance(paths.root)
        try { controller = DesktopController(paths) } catch (error: Throwable) { instance.close(); throw error }
    } catch (error: Throwable) {
        if (smoke != null) { File(smoke, "startup-error.txt").writeText(error.stackTraceToString()); throw error }
        JOptionPane.showMessageDialog(null, error.message ?: "The app could not start.", "LiveMeetingNotes", JOptionPane.ERROR_MESSAGE)
        return
    }
    application {
        val state by controller.state.collectAsState()
        val scope = rememberCoroutineScope()
        var closing by remember { mutableStateOf(false) }
        val initialBounds = remember {
            val screen = GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration
            val screenBounds = screen.bounds
            val insets = Toolkit.getDefaultToolkit().getScreenInsets(screen)
            val workArea = Rectangle(screenBounds.x + insets.left, screenBounds.y + insets.top,
                screenBounds.width - insets.left - insets.right, screenBounds.height - insets.top - insets.bottom)
            // AWT screen coordinates are logical desktop units, including on scaled displays.
            // Keep a small margin where possible, without reducing the supported minimum size.
            val width = (workArea.width - 24).coerceIn(900, 1200)
            val height = (workArea.height - 24).coerceIn(640, 820)
            Rectangle(workArea.x + ((workArea.width - width) / 2).coerceAtLeast(0),
                workArea.y + ((workArea.height - height) / 2).coerceAtLeast(0), width, height)
        }
        fun closeWindow() {
            if (closing) return
            closing = true
            scope.launch {
                try { controller.shutdown() }
                finally { instance.close(); exitApplication() }
            }
        }
        Window(
            onCloseRequest = ::closeWindow,
            title = "LiveMeetingNotes",
            state = rememberWindowState(width = initialBounds.width.dp, height = initialBounds.height.dp,
                position = WindowPosition(initialBounds.x.dp, initialBounds.y.dp))
        ) {
            LaunchedEffect(Unit) { window.minimumSize = Dimension(900, 640) }
            MaterialTheme {
                Box(Modifier.fillMaxSize()) {
                    DesktopApp(state, controller)
                    if (closing) Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)) {
                        Box(contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator()
                                Text("Finishing and saving your meeting…", Modifier.padding(24.dp))
                            }
                        }
                    }
                }
            }
            LaunchedEffect(smoke) {
                if (smoke != null) {
                    try {
                        withTimeout(60_000) { while (controller.state.value.initializing) delay(100) }
                        check(controller.state.value.error == null) { controller.state.value.error.orEmpty() }
                        withContext(Dispatchers.IO) { InstalledNativeChecks.verify(smoke) }
                        delay(2_000)
                        val bounds = Rectangle(window.locationOnScreen, window.size)
                        withContext(Dispatchers.IO) { ImageIO.write(Robot().createScreenCapture(bounds), "png", File(smoke, "installed-app.png")) }
                        File(smoke, "startup-ok.txt").writeText("Windows app initialized its SQLite library, loaded all five installed speech DLLs, passed the installed speaker worker self-test, and rendered the recorder.\n")
                    } catch (error: Throwable) { File(smoke, "startup-error.txt").writeText(error.stackTraceToString()) }
                    finally { closeWindow() }
                }
            }
        }
    }
}
