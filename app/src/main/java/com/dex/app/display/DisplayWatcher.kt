package com.dex.app.display

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.DisplayManager.DisplayListener
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Detects external displays (wired HDMI/USB-C or wireless Miracast)
 * using DisplayManager.DisplayListener.
 *
 * Only listens for displays with category PRESENTATION (external displays),
 * not the main internal display.
 */
class DisplayWatcher(private val context: Context) {

    companion object {
        private const val TAG = "DisplayWatcher"

        /**
         * Get density DPI for a display
         */
        fun getDensityDpi(display: android.view.Display): Int {
            // Use display metrics directly - getDensityDpi() is not a public API
            val metrics = android.util.DisplayMetrics()
            display.getMetrics(metrics)
            return metrics.densityDpi
        }
    }

    // Current external display (null if none)
    private val _externalDisplay = MutableStateFlow<android.view.Display?>(null)
    val externalDisplay: StateFlow<android.view.Display?> = _externalDisplay.asStateFlow()

    // Connection type inferred from display properties
    private val _connectionType = MutableStateFlow<ConnectionType>(ConnectionType.NONE)
    val connectionType: StateFlow<ConnectionType> = _connectionType.asStateFlow()

    private val displayManager: DisplayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    private val displayListener = DisplayListenerImpl()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isListening = false
    private var rescanRunnable: Runnable? = null

    enum class ConnectionType {
        NONE, WIRED, WIRELESS, UNKNOWN
    }

    data class DisplayInfo(
        val displayId: Int,
        val name: String,
        val width: Int,
        val height: Int,
        val refreshRate: Float,
        val dpi: Int,
        val connectionType: ConnectionType,
        val flags: Int,
        val ownerUid: Int
    )

    /**
     * Start listening for display changes.
     * Call from a foreground service or long-lived component.
     */
    fun start() {
        if (isListening) return
        isListening = true

        displayManager.registerDisplayListener(displayListener, null)

        // Check for existing external displays
        scanForExternalDisplays()

        Log.d(TAG, "DisplayWatcher started")
    }

    /**
     * Stop listening for display changes.
     */
    fun stop() {
        if (!isListening) return
        isListening = false

        rescanRunnable?.let { mainHandler.removeCallbacks(it) }
        rescanRunnable = null
        displayManager.unregisterDisplayListener(displayListener)
        _externalDisplay.value = null
        _connectionType.value = ConnectionType.NONE

        Log.d(TAG, "DisplayWatcher stopped")
    }

    /**
     * Manually scan for external displays.
     */
    fun scanForExternalDisplays() {
        logAllDisplays()

        val external = pickBestExternalDisplay()
        if (external != null) {
            _externalDisplay.value = external
            _connectionType.value = inferConnectionType(external)
            Log.d(TAG, "Found external display: ${describeDisplay(external)}")
        } else {
            _externalDisplay.value = null
            _connectionType.value = ConnectionType.NONE
            Log.d(TAG, "No external displays found")
        }
    }

    /**
     * Prefer PRESENTATION-category displays; fall back to any secondary display that is on.
     * Many OEMs (wireless cast) register Miracast without PRESENTATION category.
     */
    private fun pickBestExternalDisplay(): Display? {
        val presentation = displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
            .filter { isUsableExternal(it) }
        if (presentation.isNotEmpty()) {
            Log.d(TAG, "Using PRESENTATION display id=${presentation[0].displayId}")
            return presentation[0]
        }

        val secondary = displayManager.displays
            .filter { isUsableExternal(it) && it.displayId != Display.DEFAULT_DISPLAY }
        if (secondary.isNotEmpty()) {
            Log.d(TAG, "Using secondary display id=${secondary[0].displayId} (non-PRESENTATION fallback)")
            return secondary[0]
        }
        return null
    }

    private fun isUsableExternal(display: Display): Boolean {
        if (display.displayId == Display.DEFAULT_DISPLAY) return false
        if (display.state == Display.STATE_OFF) return false
        return true
    }

    private fun logAllDisplays() {
        val all = displayManager.displays
        Log.d(TAG, "scanForExternalDisplays: ${all.size} total display(s)")
        for (d in all) {
            val inPresentation = displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
                .any { it.displayId == d.displayId }
            Log.d(
                TAG,
                "  id=${d.displayId}, name=${d.name}, state=${d.state}, flags=${d.flags}, presentation=$inPresentation"
            )
        }
    }

    /** Wireless cast can register the display shortly after onDisplayAdded. */
    private fun scheduleRescan() {
        rescanRunnable?.let { mainHandler.removeCallbacks(it) }
        val runnable = Runnable {
            Log.d(TAG, "Delayed rescan after display change")
            scanForExternalDisplays()
        }
        rescanRunnable = runnable
        mainHandler.postDelayed(runnable, 750)
    }

    /**
     * Infer connection type from display properties.
     * VERIFY: This is heuristic - Display.getType() is @SystemApi.
     * We use display name, flags as hints.
     */
    private fun inferConnectionType(display: android.view.Display): ConnectionType {
        val name = display.name.lowercase()
        val flags = display.flags
        val ownerUid = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) getOwnerUid(display) else -1

        Log.d(TAG, "Display info: name=$name, flags=$flags, ownerUid=$ownerUid")

        // Heuristics for connection type
        return when {
            name.contains("hdmi") || name.contains("wired") || name.contains("dp") || name.contains("displayport") -> ConnectionType.WIRED
            name.contains("miracast") || name.contains("wireless") || name.contains("cast") -> ConnectionType.WIRELESS
            // Check flags: FLAG_SECURE often set for wireless, FLAG_PRESENTATION for both
            (flags and android.view.Display.FLAG_SECURE) != 0 -> ConnectionType.WIRELESS
            // Owner UID: system UID (1000) for wired, may vary for wireless
            ownerUid == 1000 -> ConnectionType.WIRED
            else -> ConnectionType.UNKNOWN
        }
    }

    private fun getOwnerUid(display: android.view.Display): Int {
        // getOwnerUid() was added in API 30 (Android R) - use reflection
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return try {
                val method = android.view.Display::class.java.getMethod("getOwnerUid")
                method.invoke(display) as Int
            } catch (e: Exception) {
                -1
            }
        }
        return -1
    }

    /**
     * Create a DisplayInfo object with all relevant details.
     */
    fun getDisplayInfo(): DisplayInfo? {
        val display = _externalDisplay.value ?: return null
        val metrics = android.util.DisplayMetrics()
        display.getMetrics(metrics)
        return DisplayInfo(
            displayId = display.displayId,
            name = display.name,
            width = metrics.widthPixels,
            height = metrics.heightPixels,
            refreshRate = display.refreshRate,
            dpi = getDensityDpi(display),
            connectionType = _connectionType.value,
            flags = display.flags,
            ownerUid = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) getOwnerUid(display) else -1
        )
    }

    private fun describeDisplay(display: android.view.Display): String {
        val metrics = android.util.DisplayMetrics()
        display.getMetrics(metrics)
        return "id=${display.displayId}, name=${display.name}, ${metrics.widthPixels}x${metrics.heightPixels}@${display.refreshRate}Hz, dpi=${getDensityDpi(display)}, flags=${display.flags}"
    }

    /**
     * DisplayListener implementation.
     * Called on the main thread.
     */
    private inner class DisplayListenerImpl : DisplayListener {
        override fun onDisplayAdded(displayId: Int) {
            Log.d(TAG, "onDisplayAdded: $displayId")
            if (displayId == Display.DEFAULT_DISPLAY) return
            scheduleRescan()
        }

        override fun onDisplayRemoved(displayId: Int) {
            Log.d(TAG, "onDisplayRemoved: $displayId")
            if (_externalDisplay.value?.displayId == displayId) {
                _externalDisplay.value = null
                _connectionType.value = ConnectionType.NONE
                Log.d(TAG, "External display removed")
            } else {
                scheduleRescan()
            }
        }

        override fun onDisplayChanged(displayId: Int) {
            Log.d(TAG, "onDisplayChanged: $displayId")
            if (displayId == Display.DEFAULT_DISPLAY) return
            scheduleRescan()
        }
    }

    /**
     * Scan for external displays - can be called from a broadcast receiver.
     */
    fun scan() {
        scanForExternalDisplays()
    }

    /**
     * Lifecycle-aware helper for Activities/Fragments.
     * Automatically starts/stops with lifecycle.
     */
    fun bindToLifecycle(owner: LifecycleOwner) {
        val observer = object : LifecycleEventObserver {
            override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
                when (event) {
                    Lifecycle.Event.ON_START -> start()
                    Lifecycle.Event.ON_STOP -> stop()
                    else -> Unit
                }
            }
        }
        owner.lifecycle.addObserver(observer)
    }
}