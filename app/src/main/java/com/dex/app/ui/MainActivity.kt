package com.dex.app.ui

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import com.dex.app.R
import com.dex.app.display.DisplayWatcher
import com.dex.app.service.DisplayWatcherService
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var displayWatcher: DisplayWatcher
    private var pendingDisplayId = -1
    private var confirmationDialogShowing = false

    // Views
    private lateinit var tvStatus: TextView
    private lateinit var tvConnectionType: TextView
    private lateinit var btnAction: Button
    private lateinit var cvDisplayInfo: CardView
    private lateinit var tvDisplayName: TextView
    private lateinit var tvDisplayId: TextView
    private lateinit var tvDisplaySize: TextView
    private lateinit var tvDisplayRefresh: TextView
    private lateinit var tvDisplayDpi: TextView

    private val confirmationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val displayId = intent?.getIntExtra(DisplayWatcherService.EXTRA_DISPLAY_ID, -1) ?: -1
            if (displayId != -1) {
                pendingDisplayId = displayId
                showConfirmationDialog()
                Log.d(TAG, "Received confirmation request for display $displayId")
            }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        // Handle confirmation request from DisplayWatcherService
        if (intent?.action == DisplayWatcherService.ACTION_SHOW_DEX_CONFIRMATION) {
            val displayId = intent.getIntExtra(DisplayWatcherService.EXTRA_DISPLAY_ID, -1)
            if (displayId != -1) {
                pendingDisplayId = displayId
                showConfirmationDialog()
                Log.d(TAG, "Received confirmation request via onNewIntent for display $displayId")
            }
        }
        setIntent(intent) // Important for subsequent onNewIntent calls
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(this, R.string.error_permission_denied, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize views
        tvStatus = findViewById(R.id.tv_status)
        tvConnectionType = findViewById(R.id.tv_connection_type)
        btnAction = findViewById(R.id.btn_action)
        cvDisplayInfo = findViewById(R.id.cv_display_info)
        tvDisplayName = findViewById(R.id.tv_display_name)
        tvDisplayId = findViewById(R.id.tv_display_id)
        tvDisplaySize = findViewById(R.id.tv_display_size)
        tvDisplayRefresh = findViewById(R.id.tv_display_refresh)
        tvDisplayDpi = findViewById(R.id.tv_display_dpi)

        displayWatcher = DisplayWatcher(this)
        displayWatcher.bindToLifecycle(this)

        // Register confirmation receiver
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(
                confirmationReceiver,
                IntentFilter(DisplayWatcherService.ACTION_SHOW_DEX_CONFIRMATION),
                RECEIVER_NOT_EXPORTED
            )
        } else {
            registerReceiver(
                confirmationReceiver,
                IntentFilter(DisplayWatcherService.ACTION_SHOW_DEX_CONFIRMATION)
            )
        }

        // Request notification permission (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        // Start the display watcher service
        startDisplayWatcherService()

        handleConfirmationIntent(intent)

        // Observe display changes
        lifecycleScope.launch {
            var previousDisplay: android.view.Display? = null
            displayWatcher.externalDisplay.collect { display ->
                updateUI(display)
                if (display != null && previousDisplay == null) {
                    maybePromptForDex(display.displayId)
                }
                previousDisplay = display
            }
        }

        lifecycleScope.launch {
            displayWatcher.connectionType.collect { connectionType ->
                tvConnectionType.text = "Type: $connectionType"
            }
        }

        // Set button click listener.
        // Manual path (flowchart, purple branch): if no display is known
        // yet, do NOT just jump to cast settings – offer Wired (rescan)
        // and Wireless (cast screen), because auto-trigger failure is
        // exactly the case where the user opens the app manually.
        btnAction.setOnClickListener {
            val display = displayWatcher.externalDisplay.value
            if (display != null) {
                startDex(display.displayId)
            } else {
                showManualStartChoice()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        displayWatcher.scan()
        sendBroadcast(
            Intent(DisplayWatcherService.ACTION_SCAN_DISPLAYS).setPackage(packageName)
        )
        displayWatcher.externalDisplay.value?.let { maybePromptForDex(it.displayId) }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(confirmationReceiver)
    }

    private fun handleConfirmationIntent(intent: Intent?) {
        if (intent?.action != DisplayWatcherService.ACTION_SHOW_DEX_CONFIRMATION) return
        val displayId = intent.getIntExtra(DisplayWatcherService.EXTRA_DISPLAY_ID, -1)
        if (displayId != -1) {
            pendingDisplayId = displayId
            showConfirmationDialog()
        }
    }

    private fun maybePromptForDex(displayId: Int) {
        val prefs = getSharedPreferences("dex_prefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("show_confirmation", true)) return
        if (prefs.getInt("last_prompted_display_id", -1) == displayId) return
        prefs.edit().putInt("last_prompted_display_id", displayId).apply()
        pendingDisplayId = displayId
        showConfirmationDialog()
    }

    private fun updateUI(display: android.view.Display?) {
        if (display != null) {
            // Display connected
            tvStatus.text = getString(R.string.dex_status_connected)
            btnAction.text = getString(R.string.start_dex)
            cvDisplayInfo.visibility = View.VISIBLE

            // Update display info
            tvDisplayName.text = "Name: ${display.name}"
            tvDisplayId.text = "ID: ${display.displayId}"
            tvDisplaySize.text = "Size: ${display.width} x ${display.height}"
            tvDisplayRefresh.text = "Refresh: ${display.refreshRate}Hz"
            tvDisplayDpi.text = "DPI: ${DisplayWatcher.getDensityDpi(display)}"
        } else {
            // Display disconnected
            tvStatus.text = getString(R.string.dex_status_disconnected)
            btnAction.text = getString(R.string.wireless_connect)
            cvDisplayInfo.visibility = View.GONE
        }
    }

    private fun startDisplayWatcherService() {
        val intent = Intent(this, DisplayWatcherService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        Log.d(TAG, "Started DisplayWatcherService")
    }

    private fun showManualStartChoice() {
        AlertDialog.Builder(this)
            .setTitle(R.string.manual_choice_title)
            .setMessage(R.string.manual_choice_message)
            .setPositiveButton(R.string.manual_wired) { dialog, _ ->
                dialog.dismiss()
                rescanAndStartDex()
            }
            .setNegativeButton(R.string.manual_wireless) { dialog, _ ->
                dialog.dismiss()
                openWirelessCast()
            }
            .show()
    }

    private fun rescanAndStartDex() {
        // Synchronous scan first, then ask the service to scan too.
        // DisplayWatcher.scan() updates externalDisplay.value directly,
        // so a display that auto-detection missed (delayed rescan, service
        // killed, last-prompted flag) can still be found here.
        displayWatcher.scan()
        sendBroadcast(
            Intent(DisplayWatcherService.ACTION_SCAN_DISPLAYS).setPackage(packageName)
        )
        val display = displayWatcher.externalDisplay.value
        if (display != null) {
            Log.d(TAG, "Manual rescan found display ${display.displayId}, starting DeX")
            startDex(display.displayId)
        } else {
            Log.d(TAG, "Manual rescan found no external display")
            Toast.makeText(this, R.string.wireless_no_display, Toast.LENGTH_SHORT).show()
        }
    }

    private fun startDex(displayId: Int) {
        val intent = Intent(this, DisplayWatcherService::class.java).apply {
            action = DisplayWatcherService.ACTION_START_DEX
            putExtra(DisplayWatcherService.EXTRA_DISPLAY_ID, displayId)
        }
        // startForegroundService (not startService) so a restart of a
        // killed foreground service does not hit Android 12+ restrictions.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        Toast.makeText(this, R.string.toast_dex_started, Toast.LENGTH_SHORT).show()
    }

    private fun openWirelessCast() {
        try {
            val intent = Intent(Settings.ACTION_CAST_SETTINGS)
            startActivity(intent)
            Toast.makeText(this, R.string.wireless_connecting, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open cast settings", e)
            Toast.makeText(this, R.string.wireless_no_display, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showConfirmationDialog() {
        if (confirmationDialogShowing || pendingDisplayId == -1) return

        val prefs = getSharedPreferences("dex_prefs", Context.MODE_PRIVATE)
        val showConfirmation = prefs.getBoolean("show_confirmation", true)

        if (!showConfirmation) {
            startDex(pendingDisplayId)
            return
        }

        confirmationDialogShowing = true
        AlertDialog.Builder(this)
            .setTitle(R.string.confirm_dex_title)
            .setMessage(R.string.confirm_dex_message)
            .setPositiveButton(R.string.confirm_dex_yes) { dialog, _ ->
                startDex(pendingDisplayId)
                dialog.dismiss()
            }
            .setNegativeButton(R.string.confirm_dex_no) { dialog, _ ->
                dialog.dismiss()
            }
            .setNeutralButton(R.string.confirm_dex_dont_ask) { dialog, _ ->
                prefs.edit()
                    .putBoolean("show_confirmation", false)
                    .putBoolean("auto_start", true)
                    .apply()
                startDex(pendingDisplayId)
                dialog.dismiss()
            }
            .setOnDismissListener {
                confirmationDialogShowing = false
                pendingDisplayId = -1
            }
            .setCancelable(true)
            .show()
    }
}
