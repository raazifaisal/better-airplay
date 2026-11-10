package dev.better.airplay.data.remote

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import dev.better.airplay.domain.model.RemoteCommand
import dev.better.airplay.domain.model.RemoteTarget
import dev.better.airplay.domain.port.RemoteControl
import java.io.IOException
import java.net.HttpURLConnection
import java.net.Inet6Address
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * [RemoteControl] over DACP: discovers senders' `_dacp._tcp` services via NsdManager, resolves them,
 * and sends `GET /ctrl-int/1/<cmd>` with the session's Active-Remote token.
 * Discovered services are kept; a failed connection triggers a re-resolve instead of forgetting the sender.
 */
class DacpRemoteControl(context: Context) : RemoteControl {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val io = Executors.newSingleThreadExecutor()
    private val services = ConcurrentHashMap<String, NsdServiceInfo>()   // DACP-ID -> discovered service
    private val endpoints = ConcurrentHashMap<String, String>()          // DACP-ID -> "http://host:port"
    private val resolveLock = Object()
    @Volatile private var discovering = false

    private val discovery = object : NsdManager.DiscoveryListener {
        override fun onServiceFound(info: NsdServiceInfo) {
            val id = idOf(info)
            services[id] = info
            if (!endpoints.containsKey(id)) io.execute { resolveBlocking(id) }
        }
        override fun onServiceLost(info: NsdServiceInfo) {
            val id = idOf(info); services.remove(id); endpoints.remove(id)
        }
        override fun onDiscoveryStarted(serviceType: String) = Unit
        override fun onDiscoveryStopped(serviceType: String) { discovering = false }
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            discovering = false; Log.w(TAG, "dacp discovery failed $errorCode")
        }
        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
    }

    init { ensureDiscovery() }   // so the endpoint is usually resolved before the first button press

    override fun send(target: RemoteTarget, command: RemoteCommand) {
        ensureDiscovery()
        val id = target.dacpId.uppercase()
        io.execute {
            for (attempt in 1..2) {
                val base = endpoints[id] ?: waitAndResolve(id) ?: run {
                    Log.w(TAG, "no DACP endpoint for $id; dropped $command"); return@execute
                }
                try {
                    val conn = URL(base + Dacp.path(command)).openConnection() as HttpURLConnection
                    conn.connectTimeout = 1500; conn.readTimeout = 1500
                    conn.setRequestProperty("Active-Remote", target.activeRemote)
                    conn.setRequestProperty("Host", "starlight.local.")
                    val code = conn.responseCode
                    conn.disconnect()
                    Log.i(TAG, "dacp $command -> $code")
                    return@execute
                } catch (e: IOException) {
                    // Address may have changed (phone switched networks / port): resolve again once.
                    Log.w(TAG, "dacp $command failed (${e.message}); re-resolving")
                    endpoints.remove(id)
                }
            }
        }
    }

    private fun ensureDiscovery() {
        if (discovering) return
        discovering = true
        try { nsd.discoverServices(Dacp.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery) }
        catch (e: Exception) { discovering = false; Log.w(TAG, "discoverServices: ${e.message}") }
    }

    /** Wait up to ~2 s for discovery, then resolve synchronously. Runs on [io]. */
    private fun waitAndResolve(id: String): String? {
        repeat(20) {
            endpoints[id]?.let { return it }
            if (services.containsKey(id)) return resolveBlocking(id)
            Thread.sleep(100)
        }
        return endpoints[id]
    }

    /** NsdManager resolves one service at a time; serialize and wait for the callback. */
    private fun resolveBlocking(id: String): String? {
        val info = services[id] ?: return null
        synchronized(resolveLock) {
            endpoints[id]?.let { return it }
            val done = java.util.concurrent.CountDownLatch(1)
            @Suppress("DEPRECATION")
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onServiceResolved(si: NsdServiceInfo) {
                    si.host?.let { host ->
                        val h = if (host is Inet6Address) "[${host.hostAddress?.substringBefore('%')}]" else host.hostAddress
                        endpoints[id] = "http://$h:${si.port}"
                        Log.i(TAG, "dacp endpoint $id -> ${endpoints[id]}")
                    }
                    done.countDown()
                }
                override fun onResolveFailed(si: NsdServiceInfo, errorCode: Int) {
                    Log.w(TAG, "dacp resolve failed $errorCode"); done.countDown()
                }
            })
            done.await(2, java.util.concurrent.TimeUnit.SECONDS)
            return endpoints[id]
        }
    }

    private fun idOf(info: NsdServiceInfo) = info.serviceName.removePrefix("iTunes_Ctrl_").uppercase()

    private companion object { const val TAG = "BetterAirPlay/dacp" }
}
