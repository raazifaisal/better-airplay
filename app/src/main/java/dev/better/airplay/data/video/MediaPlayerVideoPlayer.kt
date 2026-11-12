package dev.better.airplay.data.video

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import dev.better.airplay.data.engine.AndroidVideoSurface
import dev.better.airplay.domain.model.VideoError
import dev.better.airplay.domain.model.VideoPlaybackInfo
import dev.better.airplay.domain.port.VideoPlayer
import dev.better.airplay.domain.port.VideoSurface

/**
 * [VideoPlayer] on the platform [MediaPlayer] (plays HLS and progressive http(s) natively, no extra
 * dependencies). MediaPlayer is single-threaded: every command is posted to the main looper and all
 * state below is only touched there. Progress is sampled twice a second and pushed to [listener].
 */
class MediaPlayerVideoPlayer(private val context: Context) : VideoPlayer {

    @Volatile override var listener: VideoPlayer.Listener? = null

    private val main = Handler(Looper.getMainLooper())

    // ---- main-thread state ----
    private var player: MediaPlayer? = null
    /** Bumped for every new player so callbacks from a released one are ignored. */
    private var generation = 0
    private var surface: Surface? = null
    private var prepared = false
    private var completed = false
    private var buffering = false
    private var playIntent = true
    private var pendingSeekMs = 0L

    @Volatile private var snapshot = VideoPlaybackInfo.IDLE

    private val poll = object : Runnable {
        override fun run() {
            if (player == null) return
            publish()
            main.postDelayed(this, POLL_MS)
        }
    }

    override fun play(url: String, startSec: Double) = post {
        releasePlayer()
        val gen = ++generation
        prepared = false; completed = false; buffering = true; playIntent = true
        pendingSeekMs = (startSec * 1000).toLong().coerceAtLeast(0)
        Log.i(TAG, "video: load ${redact(url)} at ${startSec}s")
        val p = MediaPlayer()
        player = p
        p.setAudioAttributes(
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build()
        )
        surface?.let { trySetSurface(p, it) }
        p.setOnPreparedListener { if (gen == generation) onPrepared(it) }
        p.setOnCompletionListener {
            if (gen != generation) return@setOnCompletionListener
            Log.i(TAG, "video: completed")
            completed = true
            publish()
        }
        p.setOnErrorListener { _, what, extra ->
            if (gen == generation) fail(what, extra)
            true
        }
        p.setOnInfoListener { _, what, _ ->
            if (gen == generation) when (what) {
                MediaPlayer.MEDIA_INFO_BUFFERING_START -> { buffering = true; publish() }
                MediaPlayer.MEDIA_INFO_BUFFERING_END -> { buffering = false; publish() }
                MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START -> Log.i(TAG, "video: first frame")
            }
            false
        }
        p.setOnVideoSizeChangedListener { _, w, h -> if (gen == generation) listener?.onVideoSize(w, h) }
        p.setOnSeekCompleteListener { if (gen == generation) publish() }
        try {
            p.setDataSource(context, Uri.parse(url), HEADERS)
            p.prepareAsync()
        } catch (e: Exception) {
            Log.e(TAG, "video: cannot open ${redact(url)}", e)
            fail(MediaPlayer.MEDIA_ERROR_UNKNOWN, MediaPlayer.MEDIA_ERROR_IO)
            return@post
        }
        main.removeCallbacks(poll)
        main.postDelayed(poll, POLL_MS)
    }

    private fun onPrepared(p: MediaPlayer) {
        prepared = true
        buffering = false
        Log.i(TAG, "video: prepared, duration ${p.duration} ms, ${p.videoWidth}x${p.videoHeight}")
        if (pendingSeekMs > 0) p.seekTo(pendingSeekMs, MediaPlayer.SEEK_CLOSEST_SYNC)
        pendingSeekMs = 0
        if (playIntent) p.start()
        publish()
    }

    override fun seek(positionSec: Double) = post {
        val ms = (positionSec * 1000).toLong().coerceAtLeast(0)
        val p = player ?: return@post
        if (prepared && !completed) p.seekTo(ms, MediaPlayer.SEEK_CLOSEST_SYNC) else pendingSeekMs = ms
    }

    override fun setPlaying(playing: Boolean) = post {
        playIntent = playing
        val p = player ?: return@post
        if (prepared && !completed) {
            try { if (playing) p.start() else p.pause() } catch (e: IllegalStateException) { Log.w(TAG, "video: rate", e) }
        }
        publish()
    }

    override fun stop() = post {
        releasePlayer()
        snapshot = VideoPlaybackInfo.IDLE
    }

    override fun setSurface(surface: VideoSurface?) {
        val s = (surface as? AndroidVideoSurface)?.surface
        post {
            this.surface = s
            player?.let { trySetSurface(it, s) }
        }
    }

    override fun info(): VideoPlaybackInfo = snapshot

    override fun release() = post { releasePlayer() }

    // ---- internals (main thread) ----

    private fun publish() {
        val p = player ?: return
        snapshot = try {
            VideoPlaybackInfo.fromPlayer(
                durationMs = if (prepared) p.duration.toLong() else -1,
                positionMs = if (prepared) p.currentPosition.toLong() else 0,
                prepared = prepared, playIntent = playIntent, buffering = buffering, completed = completed,
            )
        } catch (e: IllegalStateException) {
            return
        }
        listener?.onInfo(snapshot)
    }

    private fun fail(what: Int, extra: Int) {
        Log.e(TAG, "video: error what=$what extra=$extra")
        releasePlayer()
        snapshot = VideoPlaybackInfo.ENDED
        listener?.onError(VideoError(what, extra))
    }

    private fun releasePlayer() {
        main.removeCallbacks(poll)
        val p = player ?: return
        player = null
        generation++
        prepared = false
        try { p.reset() } catch (_: Exception) {}
        p.release()
    }

    private fun trySetSurface(p: MediaPlayer, s: Surface?) {
        try {
            p.setSurface(s?.takeIf { it.isValid })
        } catch (e: Exception) {
            Log.w(TAG, "video: surface rejected", e)
        }
    }

    private inline fun post(crossinline block: () -> Unit) {
        if (Looper.myLooper() == main.looper) block() else main.post { block() }
    }

    private companion object {
        const val TAG = "BetterAirPlay"
        const val POLL_MS = 500L
        /** Some CDNs only serve Apple clients; this is what an Apple TV sends. */
        val HEADERS = mapOf("User-Agent" to "AppleCoreMedia/1.0.0.21J354 (Apple TV; U; CPU OS 17_0 like Mac OS X; en_us)")

        /** Logs without query strings (they carry signed tokens). */
        fun redact(url: String) = url.substringBefore('?')
    }
}
