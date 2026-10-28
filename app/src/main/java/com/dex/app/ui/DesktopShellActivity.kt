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
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.activity.ViewTreeOnBackPressedDispatcherOwner
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.ViewTreeSavedStateRegistryOwner
import com.dex.app.service.DisplayWatcherService
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
 * FIX (manual Start DeX crash, M1): a ComposeView hosted outside an
 * Activity/Fragment must be given ALL of its ViewTree owners, not just a
 * LifecycleOwner. The M1 code only set ViewTreeLifecycleOwner, and only
 * added the androidx.savedstate dependency without wiring it – so the
 * composition could throw IllegalStateException ("ViewTree…Owner not
 * found") as soon as it attached on the external display, i.e. exactly
 * when the user tapped Start DeX. This version makes the Presentation
 * itself a LifecycleOwner, a SavedStateRegistryOwner and an
 * OnBackPressedDispatcherOwner, sets all three owners on the ComposeView
 * BEFORE setContent, uses the Presentation (not the phone Activity) as
 * the ComposeView context, and disposes the composition when the
 * Presentation stops. It also notifies DisplayWatcherService when the
 * shell dies, so a crash / close does not leave the service stuck in
 * "DeX already running" and make the next Start DeX tap a no-op.
 */
class DesktopShellActivity : ComponentActivity() {

    companion object {
        private const val TAG = "DesktopShellActivity"
        const val EXTRA_DISPLAY_ID = "display_id"
    }

    private var presentation: DesktopPresentation? = null
    private val scope = CoroutineScope(Dispatchers.Main.immediate + Job())
    private var stoppedNotified = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val displayId = intent.getIntExtra(EXTRA_DISPLAY_ID, -1)
        if (displayId == -1 || displayId == Display.DEFAULT_DISPLAY) {
            Log.e(TAG, "No valid external display ID provided ($displayId), finishing")
            notifyStoppedAndFinish()
            return
        }

        val displayManager = getSystemService(DisplayManager::class.java)
        val display = displayManager.getDisplay(displayId)

        if (display == null) {
            Log.e(TAG, "Display $displayId not found (stale / removed), finishing")
            notifyStoppedAndFinish()
            return
        }

        Log.d(TAG, "Creating Presentation for display $displayId: " +
                "${display.name} ${display.width}x${display.height}")

        // Create and show the Presentation on external display.
        // show() can throw (e.g. BadToken) for a display that cannot host
        // a Presentation – that must reset the service flag, not leave
        // the app in a half-started state.
        presentation = DesktopPresentation(this, display)
        try {
            presentation?.show()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show Presentation on display $displayId", e)
            presentation = null
            notifyStoppedAndFinish()
            return
        }

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
                notifyStopped()
                Log.d(TAG, "DesktopShellActivity destroyed")
            }
        })
    }

    private fun notifyStopped() {
        if (stoppedNotified) return
        stoppedNotified = true
        sendBroadcast(
            Intent(DisplayWatcherService.ACTION_DEX_STOPPED).setPackage(packageName)
        )
    }

    private fun notifyStoppedAndFinish() {
        notifyStopped()
        finish()
    }

    private val stopReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.d(TAG, "Received STOP_DEX broadcast, finishing")
            finish()
        }
    }

    /**
     * Presentation subclass that renders the DeX desktop on the external display.
     *
     * Implements LifecycleOwner, SavedStateRegistryOwner and
     * OnBackPressedDispatcherOwner so a ComposeView inside it has every
     * ViewTree owner Compose requires.
     */
    inner class DesktopPresentation(
        outerContext: Context,
        display: Display
    ) : Presentation(outerContext, display), LifecycleOwner,
        SavedStateRegistryOwner, OnBackPressedDispatcherOwner {

        private val ptag = "DesktopPresentation"

        // LifecycleRegistry to provide lifecycle to ComposeView.
        // Created in init (not lazily) so it exists before show() -> onCreate().
        private val lifecycleRegistry = LifecycleRegistry(this)

        override val lifecycle: Lifecycle
            get() = lifecycleRegistry

        private val savedStateRegistryController = SavedStateRegistryController.create(this)

        override val savedStateRegistry: SavedStateRegistry
            get() = savedStateRegistryController.savedStateRegistry

        override val onBackPressedDispatcher: OnBackPressedDispatcher =
            OnBackPressedDispatcher { dismiss() }

        init {
            // SavedStateRegistry must be attached before any view looks it up.
            savedStateRegistryController.performAttach()
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        }

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            savedStateRegistryController.performRestore(savedInstanceState)

            // Make the presentation fullscreen
            window?.setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                WindowManager.LayoutParams.FLAG_FULLSCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            )

            // M1: Full Compose desktop environment via ComposeView.
            // CRITICAL: use the Presentation as the context (this@DesktopPresentation),
            // not the phone Activity – the Presentation context carries the
            // external display's resources/metrics. Set ALL ViewTree owners
            // before setContent, and dispose on lifecycle destroy.
            val composeView = ComposeView(this@DesktopPresentation).apply {
                ViewTreeLifecycleOwner.set(this, this@DesktopPresentation)
                ViewTreeSavedStateRegistryOwner.set(this, this@DesktopPresentation)
                ViewTreeOnBackPressedDispatcherOwner.set(this, this@DesktopPresentation)
                setViewCompositionStrategy(
                    ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
                )
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

            // Dialog/Presentation has no onResume callback, so drive the
            // lifecycle to RESUMED here – this is what lets the
            // WindowRecomposer actually run the composition.
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

            Log.d(ptag, "Presentation created with Compose desktop for display " +
                    "${display.displayId} (${display.width}x${display.height})")
        }

        override fun onStop() {
            // Dismiss path: drive the lifecycle down so the composition is
            // disposed (DisposeOnViewTreeLifecycleDestroyed) instead of leaking.
            if (lifecycleRegistry.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            }
            if (lifecycleRegistry.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
            }
            if (lifecycleRegistry.currentState != Lifecycle.State.DESTROYED) {
                lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
            }
            super.onStop()
        }

        override fun onDisplayChanged() {
            super.onDisplayChanged()
            Log.d(ptag, "Display changed: ${display.displayId} ${display.width}x${display.height}")
        }
    }
}
