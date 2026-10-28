package com.dex.app.ui

import android.app.Presentation
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Display
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewTreeLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job

/**
 * DesktopShellActivity – manages the Presentation on the external display.
 *
 * M0 showed a plain TextView; M1 replaces it with the full Compose desktop
 * environment: wallpaper, taskbar, start menu, draggable/resizable windows,
 * and six internal demo apps (File Manager, Terminal, Settings, Browser,
 * Calculator, Text Editor).
 *
 * The Compose tree lives inside a ComposeView within DesktopPresentation's
 * content view which renders on the external display. All window/app state
 * is held in DesktopState (see DesktopContentMinimal.kt) and driven by Compose.
 *
 * FIX: ComposeView inside Presentation needs ViewTreeLifecycleOwner set manually.
 * We make Presentation implement LifecycleOwner via LifecycleRegistry.
 */
class DesktopShellActivity : ComponentActivity() {

    companion object {
        private const val TAG = "DesktopShellActivity"
        const val EXTRA_DISPLAY_ID = "display_id"
    }

    private var presentation: DesktopPresentation? = null
    private val scope = CoroutineScope(Dispatchers.Main.immediate + Job())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val displayId = intent.getIntExtra(EXTRA_DISPLAY_ID, -1)
        if (displayId == -1) {
            Log.e(TAG, "No display ID provided, finishing")
            finish()
            return
        }

        val displayManager = getSystemService(DisplayManager::class.java)
        val display = displayManager.getDisplay(displayId)

        if (display == null) {
            Log.e(TAG, "Display $displayId not found, finishing")
            finish()
            return
        }

        Log.d(TAG, "Creating Presentation for display $displayId: " +
                "${display.name} ${display.width}x${display.height}")

        // Create and show the Presentation on external display
        presentation = DesktopPresentation(this, display)
        presentation?.show()

        // Listen for stop broadcast from DisplayWatcherService
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(
                stopReceiver,
                IntentFilter("com.dex.app.STOP_DEX"),
                RECEIVER_NOT_EXPORTED
            )
        } else {
            registerReceiver(stopReceiver, IntentFilter("com.dex.app.STOP_DEX"))
        }

        // Clean up on destroy
        lifecycle.addObserver(object : androidx.lifecycle.DefaultLifecycleObserver {
            override fun onDestroy(owner: androidx.lifecycle.LifecycleOwner) {
                presentation?.dismiss()
                presentation = null
                try { unregisterReceiver(stopReceiver) } catch (_: Exception) {}
                scope.coroutineContext[Job]?.cancel()
                Log.d(TAG, "DesktopShellActivity destroyed")
            }
        })
    }

    private val stopReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.d(TAG, "Received STOP_DEX broadcast, finishing")
            finish()
        }
    }

    /**
     * Presentation subclass that renders the DeX desktop on the external display.
     * M1: Full Compose desktop with wallpaper, taskbar, start menu, windowed apps.
     *
     * Implements LifecycleOwner to provide lifecycle to ComposeView.
     */
    inner class DesktopPresentation(
        outerContext: Context,
        display: Display
    ) : Presentation(outerContext, display), LifecycleOwner {

        private val PTAG = "DesktopPresentation"
        private val context = outerContext
        // LifecycleRegistry to provide lifecycle to ComposeView
        private val lifecycleRegistry = LifecycleRegistry(this)

        override val lifecycle: LifecycleRegistry
            get() = lifecycleRegistry

        init {
            // Mark as created initially
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        }

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)

            // Make the presentation fullscreen
            window?.setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                WindowManager.LayoutParams.FLAG_FULLSCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            )

            // M1: Full Compose desktop environment via ComposeView
            val composeView = ComposeView(context).apply {
                // CRITICAL: Provide ViewTreeLifecycleOwner for ComposeView inside Presentation
                ViewTreeLifecycleOwner.set(this, this@DesktopPresentation)
                setContent {
                    MaterialTheme {
                        Surface(
                            modifier = Modifier.fillMaxSize(),
                            color = Color(0xFF0D1B2A)
                        ) {
                            DesktopContentMinimal()
                        }
                    }
                }
            }
            setContentView(composeView)

            // Continue lifecycle
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

            Log.d(PTAG, "Presentation created with Compose desktop for display " +
                    "${display.displayId} (${display.width}x${display.height})")
        }

        override fun onDisplayChanged() {
            super.onDisplayChanged()
            Log.d(PTAG, "Display changed: ${display.displayId} ${display.width}x${display.height}")
        }
    }
}