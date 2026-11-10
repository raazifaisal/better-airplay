package dev.better.airplay.data.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * Advertises _airplay._tcp / _raop._tcp through Android's system mDNS responder.
 * The system daemon keeps answering queries even while our process is idle.
 */
class NsdServiceAdvertiser(context: Context) {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
    private val listeners = ConcurrentHashMap<String, NsdManager.RegistrationListener>()
    private var multicastLock: WifiManager.MulticastLock? = null

    @Synchronized
    fun start() {
        if (multicastLock == null) {
            multicastLock = wifi.createMulticastLock("better-airplay").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    @Synchronized
    fun stop() {
        listeners.keys.toList().forEach { unregister(it) }
        multicastLock?.let { if (it.isHeld) it.release() }
        multicastLock = null
    }

    /** [txt] is raw DNS TXT data (length-prefixed "key=value" entries). Returns 0 on success. */
    fun register(type: String, name: String, port: Int, txt: ByteArray): Int {
        unregister(type)
        val info = NsdServiceInfo().apply {
            serviceName = name
            serviceType = type
            this.port = port
        }
        parseTxt(txt).forEach { (k, v) -> info.setAttribute(k, v) }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(si: NsdServiceInfo) = Log.i(TAG, "registered ${si.serviceName} $type:$port").let {}
            override fun onRegistrationFailed(si: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "registration failed for $type: $errorCode"); listeners.remove(type)
            }
            override fun onServiceUnregistered(si: NsdServiceInfo) = Log.i(TAG, "unregistered $type").let {}
            override fun onUnregistrationFailed(si: NsdServiceInfo, errorCode: Int) =
                Log.w(TAG, "unregistration failed for $type: $errorCode").let {}
        }
        return try {
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
            listeners[type] = listener
            0
        } catch (e: Exception) {
            Log.e(TAG, "registerService threw for $type", e)
            -65537
        }
    }

    fun unregister(type: String) {
        listeners.remove(type)?.let {
            try { nsd.unregisterService(it) } catch (e: Exception) { Log.w(TAG, "unregister $type", e) }
        }
    }

    companion object {
        private const val TAG = "BetterAirPlay/mdns"

        fun parseTxt(txt: ByteArray): List<Pair<String, String>> {
            val out = ArrayList<Pair<String, String>>()
            var i = 0
            while (i < txt.size) {
                val len = txt[i].toInt() and 0xFF
                i++
                if (len == 0 || i + len > txt.size) break
                val entry = String(txt, i, len, Charsets.UTF_8)
                i += len
                val eq = entry.indexOf('=')
                out += if (eq > 0) entry.substring(0, eq) to entry.substring(eq + 1) else entry to ""
            }
            return out
        }
    }
}
