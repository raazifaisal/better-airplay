package dev.better.airplay.data.engine

import android.util.Log
import android.view.Surface

/**
 * JNI surface of libbetterairplay (UxPlay core + media pipelines). Names must match native_bridge.cpp.
 * Static callbacks are invoked from native threads.
 */
internal object NativeBridge {
    private const val TAG = "BetterAirPlay"

    init {
        System.loadLibrary("betterairplay")
    }

    /** Receives native callbacks; set by [UxPlayEngine]. */
    interface Sink {
        fun onEvent(event: String, arg: String)
        fun onBinary(event: String, data: ByteArray)
        fun onMdnsRegister(type: String, name: String, port: Int, txt: ByteArray): Int
        fun onMdnsUnregister(type: String)
    }

    @Volatile var sink: Sink? = null

    @JvmStatic external fun nativeStart(
        name: String, hwAddr: ByteArray, keyFile: String,
        width: Int, height: Int, refreshRate: Int, maxFps: Int,
        h265: Boolean, hls: Boolean, overscanned: Boolean,
        audioDelayMicros: Int, pinMode: Int,
    ): Int

    @JvmStatic external fun nativeStop()
    @JvmStatic external fun nativeDisconnectClients()
    @JvmStatic external fun nativeSetSurface(surface: Surface?)
    @JvmStatic external fun nativeSetAudioMuted(muted: Boolean)
    @JvmStatic external fun nativeSetLowLatency(enabled: Boolean)

    // ---- called from native ----

    @JvmStatic
    fun onLog(level: Int, msg: String) {
        val text = msg.trimEnd()
        when {   // UxPlay levels follow syslog: 3=err, 4=warn, 6=info, 7=debug
            level <= 3 -> Log.e(TAG, text)
            level == 4 -> Log.w(TAG, text)
            level <= 6 -> Log.i(TAG, text)
            else -> Log.d(TAG, text)
        }
    }

    @JvmStatic
    fun onEvent(event: String, arg: String) {
        if (event != "video_frames" && event != "audio_packets") Log.i(TAG, "event $event $arg")
        sink?.onEvent(event, arg)
    }

    @JvmStatic
    fun onBinary(event: String, data: ByteArray) {
        sink?.onBinary(event, data)
    }

    @JvmStatic
    fun onMdnsRegister(type: String, name: String, port: Int, txt: ByteArray): Int =
        sink?.onMdnsRegister(type, name, port, txt) ?: -65537

    @JvmStatic
    fun onMdnsUnregister(type: String) {
        sink?.onMdnsUnregister(type)
    }
}
