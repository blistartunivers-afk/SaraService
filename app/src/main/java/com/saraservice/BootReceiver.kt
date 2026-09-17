package com.saraservice

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    private val TAG = "SaraBootReceiver"

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "Boot received: ${intent.action}")
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == "android.intent.action.QUICKBOOT_POWERON") {
            val serviceIntent = Intent(context, SaraSocketService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            Log.d(TAG, "SaraSocketService started on boot")
        }
    }
}
