package com.dex.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.dex.app.R
import com.dex.app.display.DisplayWatcher
import com.dex.app.ui.DesktopShellActivity
import com.dex.app.ui.DexConfirmationActivity
import com.dex.app.ui.MainActivity
import com.dex.app.util.DexConfirmationDismissReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Foreground service that monitors external display connections.
 * Runs continuously to detect display connect/disconnect events.
 *
 * On display connect: shows confirmation dialog on primary display (if enabled in settings)
 * On display disconnect: stops DeX mode gracefully.
 */
class DisplayWatcherService : Service() {

    companion object {
        private const val TAG = "DisplayWatcherService"
        const val CHANNEL_ID = "dex_display_watcher"
        const val CONFIRMATION_CHANNEL_ID = "dex_dex_confirmation"
        const val NOTIFICATION_ID = 1001
        const val NOTIFICATION_ID_CONFIRMATION = 2001
        const val ACTION_SHOW_DEX_CONFIRMATION = "com.dex.app.SHOW_DEX_CONFIRMATION"
        private const val PREF_LAST_PROMPTED_DISPLAY_ID = "last_prompted_display_id"
        const val ACTION_START_DEX = "com.dex.app.ACTION_START_DEX"
        const val ACTION_STOP_DEX = "com.dex.app.ACTION_STOP_DEX"
        const val ACTION_SCAN_DISPLAYS = "com.dex.app.SCAN_DISPLAYS"
        const val EXTRA_DISPLAY_ID = "display_id"
        const val EXTRA_CONNECTION_TYPE = "connection_type"
    }

    private val scanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_SCAN_DISPLAYS) {
                Log.d(TAG, "Received scan displays broadcast")
                displayWatcher.scan()
            }
        }
    }

    private lateinit var displayWatcher: DisplayWatcher
    private val scope = CoroutineScope(Dispatchers.Main.immediate + Job())
    private var isDexRunning = false
    private var currentDisplayId = -1
    private var lastDisplay: android.view.Display? = null

    override fun onCreate() {
        super.onCreate()
        displayWatcher = DisplayWatcher(this)
        createNotificationChannels()
        startForeground(NOTIFICATION_ID, createNotification())

        // Register scan receiver
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(
                scanReceiver,
                IntentFilter(ACTION_SCAN_DISPLAYS),
                RECEIVER_NOT_EXPORTED
            )
        } else {
            registerReceiver(scanReceiver, IntentFilter(ACTION_SCAN_DISPLAYS))
        }

        // Start display watching
        displayWatcher.start()

        // Observe display changes
        scope.launch {
            displayWatcher.externalDisplay.collect { display ->
                if (display != lastDisplay) {
                    lastDisplay = display
                    handleDisplayChange(display)
                }
            }
        }

        Log.d(TAG, "DisplayWatcherService created and started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Handle explicit start/stop actions
        intent?.action?.let { action ->
            when (action) {
                ACTION_START_DEX -> {
                    val displayId = intent.getIntExtra(EXTRA_DISPLAY_ID, -1)
                    if (displayId != -1) {
                        startDexOnDisplay(displayId)
                    }
                }
                ACTION_STOP_DEX -> stopDex()
            }
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.coroutineContext[Job]?.cancel()
        displayWatcher.stop()
        unregisterReceiver(scanReceiver)
        stopForeground(true)
        Log.d(TAG, "DisplayWatcherService destroyed")
        super.onDestroy()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "DeX Display Watcher",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Monitors external display connections for DeX mode"
                    setShowBadge(false)
                    lockscreenVisibility = Notification.VISIBILITY_SECRET
                }
            )
            manager.createNotificationChannel(
                NotificationChannel(
                    CONFIRMATION_CHANNEL_ID,
                    "DeX mode prompt",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Ask to start DeX when a TV or monitor connects"
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                }
            )
        }
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.dex_status_ready))
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .build()
    }

    private fun updateNotification(text: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .build()

        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun handleDisplayChange(display: android.view.Display?) {
        if (display != null) {
            // External display connected
            currentDisplayId = display.displayId
            val connectionType = displayWatcher.connectionType.value

            Log.d(TAG, "External display connected: id=${display.displayId}, type=$connectionType")
            updateNotification(getString(R.string.dex_status_connected))

            // Check if we should auto-start or show confirmation
            checkAndStartDex(display, connectionType)

        } else {
            // External display disconnected
            Log.d(TAG, "External display disconnected")
            updateNotification(getString(R.string.dex_status_disconnected))

            if (isDexRunning) {
                stopDex()
            }
            currentDisplayId = -1
            getSharedPreferences("dex_prefs", Context.MODE_PRIVATE)
                .edit()
                .remove(PREF_LAST_PROMPTED_DISPLAY_ID)
                .apply()
        }
    }

    private fun checkAndStartDex(display: android.view.Display, connectionType: DisplayWatcher.ConnectionType) {
        val prefs = getSharedPreferences("dex_prefs", Context.MODE_PRIVATE)
        val autoStart = prefs.getBoolean("auto_start", false)
        val showConfirmation = prefs.getBoolean("show_confirmation", true)

        when {
            showConfirmation -> showConfirmationDialog(display, connectionType)
            autoStart -> {
                Log.d(TAG, "Confirmation off + auto-start: launching DeX")
                startDexOnDisplay(display.displayId)
            }
            else -> {
                Log.d(
                    TAG,
                    "Display connected; confirmation disabled and auto-start off — use Start DeX in app"
                )
            }
        }
    }

    private fun showConfirmationDialog(display: android.view.Display, connectionType: DisplayWatcher.ConnectionType) {
        val prefs = getSharedPreferences("dex_prefs", Context.MODE_PRIVATE)
        if (prefs.getInt(PREF_LAST_PROMPTED_DISPLAY_ID, -1) == display.displayId) {
            Log.d(TAG, "Already prompted for display ${display.displayId}, skipping")
            return
        }
        prefs.edit().putInt(PREF_LAST_PROMPTED_DISPLAY_ID, display.displayId).apply()

        // In-app broadcast when MainActivity (or another component) is listening
        sendBroadcast(
            Intent(ACTION_SHOW_DEX_CONFIRMATION).apply {
                setPackage(packageName)
                putExtra(EXTRA_DISPLAY_ID, display.displayId)
                putExtra(EXTRA_CONNECTION_TYPE, connectionType.ordinal)
            }
        )

        val confirmIntent = Intent(this, DexConfirmationActivity::class.java).apply {
            action = DexConfirmationActivity.ACTION_SHOW
            putExtra(EXTRA_DISPLAY_ID, display.displayId)
            putExtra(EXTRA_CONNECTION_TYPE, connectionType.ordinal)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            display.displayId,
            confirmIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val dismissIntent = Intent(this, DexConfirmationDismissReceiver::class.java).apply {
            action = "com.dex.app.DISMISS_DEX_CONFIRMATION"
        }
        val dismissPending = PendingIntent.getBroadcast(
            this,
            display.displayId + 10_000,
            dismissIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, CONFIRMATION_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.confirm_dex_title))
            .setContentText(getString(R.string.confirm_dex_message))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setContentIntent(pendingIntent)
            .setFullScreenIntent(pendingIntent, true)
            .setAutoCancel(true)
            .setVibrate(longArrayOf(0, 250, 250, 250))
            .addAction(
                NotificationCompat.Action.Builder(
                    R.mipmap.ic_launcher,
                    getString(R.string.confirm_dex_yes),
                    pendingIntent
                ).build()
            )
            .addAction(
                NotificationCompat.Action.Builder(
                    R.mipmap.ic_launcher,
                    getString(R.string.confirm_dex_no),
                    dismissPending
                ).build()
            )
            .build()

        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID_CONFIRMATION, notification)

        Log.d(TAG, "Prompted DeX confirmation for display ${display.displayId}")
    }

    private fun startDexOnDisplay(displayId: Int) {
        if (isDexRunning) {
            Log.w(TAG, "DeX already running, ignoring start request")
            return
        }

        isDexRunning = true
        updateNotification(getString(R.string.dex_status_starting))

        // Launch DesktopShell as a Presentation on the external display
        val intent = Intent(this, DesktopShellActivity::class.java).apply {
            putExtra(EXTRA_DISPLAY_ID, displayId)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)

        Log.d(TAG, "Started DeX on display $displayId")
        updateNotification(getString(R.string.toast_dex_started))
    }

    private fun stopDex() {
        if (!isDexRunning) return

        isDexRunning = false
        updateNotification(getString(R.string.dex_status_stopping))

        // Send broadcast to DesktopShell to close
        val intent = Intent("com.dex.app.STOP_DEX").apply {
            addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
        }
        sendBroadcast(intent)

        Log.d(TAG, "Stopped DeX")
        updateNotification(getString(R.string.toast_dex_stopped))
    }
}