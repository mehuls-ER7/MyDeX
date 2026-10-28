package com.dex.app.ui

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.dex.app.R
import com.dex.app.service.DisplayWatcherService

/**
 * Transparent activity that shows the DeX confirmation dialog.
 * Used from high-priority notifications / full-screen intent when the app is in the background.
 */
class DexConfirmationActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "DexConfirmationActivity"
        const val ACTION_SHOW = "com.dex.app.SHOW_DEX_CONFIRMATION"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val displayId = intent.getIntExtra(DisplayWatcherService.EXTRA_DISPLAY_ID, -1)
        if (displayId == -1) {
            Log.e(TAG, "No display ID, finishing")
            finish()
            return
        }

        val prefs = getSharedPreferences("dex_prefs", Context.MODE_PRIVATE)
        val showConfirmation = prefs.getBoolean("show_confirmation", true)
        if (!showConfirmation) {
            startDex(displayId)
            finish()
            return
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.confirm_dex_title)
            .setMessage(R.string.confirm_dex_message)
            .setPositiveButton(R.string.confirm_dex_yes) { _, _ ->
                startDex(displayId)
                finish()
            }
            .setNegativeButton(R.string.confirm_dex_no) { _, _ ->
                finish()
            }
            .setNeutralButton(R.string.confirm_dex_dont_ask) { _, _ ->
                prefs.edit()
                    .putBoolean("show_confirmation", false)
                    .putBoolean("auto_start", true)
                    .apply()
                startDex(displayId)
                finish()
            }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun startDex(displayId: Int) {
        val intent = Intent(this, DisplayWatcherService::class.java).apply {
            action = DisplayWatcherService.ACTION_START_DEX
            putExtra(DisplayWatcherService.EXTRA_DISPLAY_ID, displayId)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }
}
