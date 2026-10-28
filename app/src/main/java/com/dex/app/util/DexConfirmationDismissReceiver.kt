package com.dex.app.util

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.dex.app.service.DisplayWatcherService

class DexConfirmationDismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.cancel(DisplayWatcherService.NOTIFICATION_ID_CONFIRMATION)
    }
}
