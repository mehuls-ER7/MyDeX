package com.dex.app.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.dex.app.service.DisplayWatcherService

/**
 * Boot receiver to start DisplayWatcherService on device boot.
 * Best effort - Android 12+ restricts boot receivers.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED && context != null) {
            Log.d(TAG, "Boot completed, starting DisplayWatcherService")

            val serviceIntent = Intent(context, DisplayWatcherService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start service on boot", e)
            }
        }
    }
}