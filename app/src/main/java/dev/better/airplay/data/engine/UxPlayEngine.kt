package dev.better.airplay.data.engine

import dev.better.airplay.data.discovery.NsdServiceAdvertiser
import dev.better.airplay.domain.model.ReceiverConfig
import dev.better.airplay.domain.port.EngineListener
import dev.better.airplay.domain.port.ReceiverEngine
import dev.better.airplay.domain.port.StartResult
import dev.better.airplay.domain.port.VideoSurface
import android.os.SystemClock
import java.io.File

/** [ReceiverEngine] backed by the UxPlay C core through JNI, advertising via NsdManager. */
class UxPlayEngine(
    private val advertiser: NsdServiceAdvertiser,
    private val keyFile: File,
    private val deviceId: () -> ByteArray,
) : ReceiverEngine, NativeBridge.Sink {

    @Volatile override var listener: EngineListener? = null

    override fun start(config: ReceiverConfig): StartResult {
        NativeBridge.sink = this
        advertiser.start()
        val port = NativeBridge.nativeStart(
            name = config.name,
            hwAddr = deviceId(),
            keyFile = keyFile.absolutePath,
            width = config.width,
            height = config.height,
            refreshRate = config.refreshHz,
            maxFps = config.maxFps,
            h265 = config.hevc,
            hls = config.videoHandover,
            overscanned = config.overscanned,
            audioDelayMicros = config.audioDelayMicros,
            pinMode = if (config.requirePin) 1 else 0,
        )
        return if (port > 0) StartResult.Started(port) else StartResult.Failed(port)
    }

    override fun stop() {
        NativeBridge.nativeStop()
        advertiser.stop()
    }

    override fun disconnectSenders() = NativeBridge.nativeDisconnectClients()

    override fun setVideoSurface(surface: VideoSurface?) {
        NativeBridge.nativeSetSurface((surface as? AndroidVideoSurface)?.surface)
    }

    override fun setAudioMuted(muted: Boolean) = NativeBridge.nativeSetAudioMuted(muted)

    override fun setLowLatency(enabled: Boolean) = NativeBridge.nativeSetLowLatency(enabled)

    // ---- NativeBridge.Sink ----

    override fun onEvent(event: String, arg: String) {
        NativeEventParser.parse(event, arg, SystemClock.elapsedRealtime())?.let { listener?.onEngineEvent(it) }
    }

    override fun onBinary(event: String, data: ByteArray) {
        android.util.Log.i("BetterAirPlay", "binary $event ${data.size} bytes")
        NativeEventParser.parseBinary(event, data)?.let { listener?.onEngineEvent(it) }
    }

    override fun onMdnsRegister(type: String, name: String, port: Int, txt: ByteArray): Int =
        advertiser.register(type, name, port, txt)

    override fun onMdnsUnregister(type: String) = advertiser.unregister(type)
}
