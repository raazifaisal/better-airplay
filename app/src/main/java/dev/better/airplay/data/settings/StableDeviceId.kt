package dev.better.airplay.data.settings

import android.content.Context
import java.security.SecureRandom

/**
 * Stable AirPlay device id. Android hides the real MAC, so we generate a random,
 * locally-administered unicast address once and persist it. Senders remember
 * receivers (and PIN pairings) by this id, so it must never change.
 */
class StableDeviceId(context: Context) {
    private val prefs = context.getSharedPreferences("identity", Context.MODE_PRIVATE)

    fun get(): ByteArray {
        prefs.getString(KEY, null)?.let { hex ->
            if (hex.length == 12) return ByteArray(6) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        }
        val mac = ByteArray(6).also { SecureRandom().nextBytes(it) }
        mac[0] = ((mac[0].toInt() and 0xFC) or 0x02).toByte()   // locally administered, unicast
        prefs.edit().putString(KEY, mac.joinToString("") { "%02x".format(it) }).apply()
        return mac
    }

    private companion object { const val KEY = "hw_addr" }
}
