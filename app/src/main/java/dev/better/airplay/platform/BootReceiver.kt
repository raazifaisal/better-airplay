package dev.better.airplay.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.better.airplay.App

/** Starts the receiver at boot and after app updates, so nobody has to open the app. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.i("BetterAirPlay", "boot receiver: ${intent.action}")
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> (context.applicationContext as App).container.controller.ensureRunning()
        }
    }
}
