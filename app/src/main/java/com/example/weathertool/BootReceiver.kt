package com.example.weathertool

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Restarts the hourly WeatherWorker after the device reboots so monitoring
 * continues without requiring the user to reopen the app.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val prefHelper = PreferenceHelper(context)
            if (prefHelper.monitoringEnabled) {
                WeatherWorker.schedule(context)
            }
        }
    }
}
