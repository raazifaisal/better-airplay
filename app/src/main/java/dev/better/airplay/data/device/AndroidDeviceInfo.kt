package dev.better.airplay.data.device

import android.content.Context
import android.database.ContentObserver
import android.hardware.display.DisplayManager
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Display
import dev.better.airplay.domain.model.DisplayCapabilities
import dev.better.airplay.domain.port.DeviceInfo

class AndroidDeviceInfo(private val context: Context) : DeviceInfo {

    override fun deviceName(): String =
        Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
            ?.takeIf { it.isNotBlank() } ?: Build.MODEL

    /**
     * Decoder capability, not the UI surface size: TVs often draw the UI at 1080p while video
     * goes to a 4K plane, so `wm size` / Display size under-reports what we can mirror.
     */
    override fun displayCapabilities(): DisplayCapabilities = capabilities

    /** Codec enumeration is slow and the hardware doesn't change; compute once. */
    private val capabilities: DisplayCapabilities by lazy { probeCapabilities() }

    private fun probeCapabilities(): DisplayCapabilities {
        val hevc4k60 = supports(MediaFormat.MIMETYPE_VIDEO_HEVC, 3840, 2160, 60.0)
        val avc4k = supports(MediaFormat.MIMETYPE_VIDEO_AVC, 3840, 2160, 30.0)
        val display = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        val refresh = display?.mode?.refreshRate?.toInt() ?: 60
        return DisplayCapabilities(
            maxWidth = if (hevc4k60 || avc4k) 3840 else 1920,
            maxHeight = if (hevc4k60 || avc4k) 2160 else 1080,
            hevc4k60 = hevc4k60,
            refreshHz = refresh,
        )
    }

    override fun observeDeviceName(onChange: () -> Unit): AutoCloseable {
        val callback = onChange   // avoid ambiguity with ContentObserver.onChange below
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = callback()
        }
        context.contentResolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.DEVICE_NAME), false, observer
        )
        return AutoCloseable { context.contentResolver.unregisterContentObserver(observer) }
    }

    private fun supports(mime: String, w: Int, h: Int, fps: Double): Boolean = try {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { !it.isEncoder && it.supportedTypes.any { t -> t.equals(mime, true) } }
            .filter { Build.VERSION.SDK_INT < 29 || it.isHardwareAccelerated }
            .any { info ->
                info.getCapabilitiesForType(mime).videoCapabilities?.areSizeAndRateSupported(w, h, fps) == true
            }
    } catch (e: Exception) {
        false
    }
}
