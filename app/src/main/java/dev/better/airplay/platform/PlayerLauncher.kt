package dev.better.airplay.platform

import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import dev.better.airplay.domain.model.ReceiverSnapshot
import dev.better.airplay.presentation.player.PlayerActivity

/**
 * Brings the player to the front the moment a device connects, waking the screen if needed.
 * Starting an Activity from the background is allowed because provisioning grants the
 * SYSTEM_ALERT_WINDOW app-op (tools/provision.sh).
 */
class PlayerLauncher(private val context: Context) {
    private var wasConnected = false

    @Synchronized
    fun onStatus(snap: ReceiverSnapshot) {
        val connected = snap.status.isConnected
        if (connected && !wasConnected) launch()
        wasConnected = connected
    }

    private fun launch() {
        try {
            @Suppress("DEPRECATION")
            context.getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "betterairplay:connect")
                .acquire(5_000)
            context.startActivity(
                Intent(context, PlayerActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            )
        } catch (e: Exception) {
            Log.e("BetterAirPlay", "could not open player", e)
        }
    }
}
