package dev.better.airplay.presentation.player

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Outline
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import dev.better.airplay.R
import dev.better.airplay.di.AppContainer
import dev.better.airplay.domain.model.Artwork
import dev.better.airplay.domain.model.RemoteCommand
import dev.better.airplay.presentation.components.AmbientBackgroundView
import dev.better.airplay.presentation.components.AspectFitLayout
import dev.better.airplay.presentation.components.MessageBar
import dev.better.airplay.presentation.components.Motion
import dev.better.airplay.presentation.components.ProgressLineView
import dev.better.airplay.presentation.core.ViewModelActivity
import java.util.concurrent.Executors

/** View: full-screen player. Renders [PlayerUiState]; hands its Surface to the ViewModel. */
class PlayerActivity : ViewModelActivity<PlayerUiState, PlayerAction, PlayerEffect, PlayerViewModel>(),
    SurfaceHolder.Callback {

    private lateinit var videoFrame: AspectFitLayout
    private lateinit var surfaceView: SurfaceView
    private lateinit var nowPlaying: View
    private lateinit var ambient: AmbientBackgroundView
    private lateinit var artwork: ImageView
    private lateinit var npTitle: TextView
    private lateinit var npArtist: TextView
    private lateinit var npAlbum: TextView
    private lateinit var npCaption: TextView
    private lateinit var npSourcePill: View
    private lateinit var npMeta: View
    private lateinit var npColArtist: View
    private lateinit var npColAlbum: View
    private lateinit var npProgressGroup: View
    private lateinit var progressLine: ProgressLineView
    private lateinit var npElapsed: TextView
    private lateinit var npRemaining: TextView
    private lateinit var connecting: View
    private lateinit var connectingCaption: TextView
    private lateinit var connectingPin: TextView
    private lateinit var message: MessageBar
    private lateinit var controls: View
    private lateinit var btnPlayPause: ImageButton
    private var controlsFocused = false

    private val main = Handler(Looper.getMainLooper())
    private val decoder = Executors.newSingleThreadExecutor()
    private var shownArtwork: Artwork? = null
    private var artworkLoaded = false
    private var shownPlaying: Boolean? = null
    private var latest = PlayerUiState()

    /** Samples progress against the clock 4x a second so the bar and times move smoothly. */
    private val ticker = object : Runnable {
        override fun run() {
            renderProgress()
            if (latest.mode == PlayerMode.AUDIO && latest.playing) main.postDelayed(this, 250)
        }
    }

    override fun createViewModel(container: AppContainer) = container.playerViewModel()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        setContentView(R.layout.activity_player)
        hideSystemBars()

        videoFrame = AspectFitLayout(this)
        surfaceView = SurfaceView(this).also { it.holder.addCallback(this) }
        videoFrame.addView(surfaceView)
        findViewById<FrameLayout>(R.id.video_host).addView(videoFrame, matchParent())

        ambient = AmbientBackgroundView(this)
        findViewById<FrameLayout>(R.id.ambient_host).addView(ambient, matchParent())
        progressLine = ProgressLineView(this)
        findViewById<FrameLayout>(R.id.np_progress_host).addView(progressLine,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))

        nowPlaying = findViewById(R.id.now_playing)
        artwork = findViewById(R.id.artwork)
        // Round the artwork itself (not just its hairline). android:clipToOutline in XML is only
        // read on API 31+, so set the outline and clipping in code for older TVs.
        val artRadius = 24 * resources.displayMetrics.density
        artwork.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) =
                outline.setRoundRect(0, 0, view.width, view.height, artRadius)
        }
        artwork.clipToOutline = true
        // Pause shrinks the artwork; anchor it on the left edge (vertically centred) so it stays
        // flush with the "Now Playing" wordmark above instead of drifting inward.
        artwork.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            v.pivotX = 0f
            v.pivotY = v.height / 2f
        }
        npTitle = findViewById(R.id.np_title)
        npArtist = findViewById(R.id.np_artist)
        npAlbum = findViewById(R.id.np_album)
        npCaption = findViewById(R.id.np_caption)
        npSourcePill = findViewById(R.id.np_source_pill)
        npMeta = findViewById(R.id.np_meta)
        npColArtist = findViewById(R.id.np_col_artist)
        npColAlbum = findViewById(R.id.np_col_album)
        npProgressGroup = findViewById(R.id.np_progress_group)
        npElapsed = findViewById(R.id.np_elapsed)
        npRemaining = findViewById(R.id.np_remaining)
        connecting = findViewById(R.id.connecting)
        connectingCaption = findViewById(R.id.connecting_caption)
        connectingPin = findViewById(R.id.connecting_pin)
        message = MessageBar(findViewById(R.id.message))
        controls = findViewById(R.id.np_controls)
        btnPlayPause = findViewById(R.id.btn_play_pause)
        bindControl(findViewById(R.id.btn_prev), RemoteCommand.PREVIOUS)
        bindControl(btnPlayPause, RemoteCommand.PLAY_PAUSE)
        bindControl(findViewById(R.id.btn_next), RemoteCommand.NEXT)
        showPlaceholderArtwork()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        decoder.shutdownNow()
        super.onDestroy()
    }

    override fun render(state: PlayerUiState) {
        latest = state
        connecting.visibility = if (state.mode == PlayerMode.CONNECTING) View.VISIBLE else View.GONE
        nowPlaying.visibility = if (state.mode == PlayerMode.AUDIO) View.VISIBLE else View.GONE
        connectingCaption.text = state.caption
        connectingPin.visibility = if (state.pin != null) View.VISIBLE else View.GONE
        connectingPin.text = state.pin

        npTitle.text = state.title
        val hasArtist = !state.artist.isNullOrBlank()
        val hasAlbum = !state.album.isNullOrBlank()
        npArtist.text = state.artist
        npAlbum.text = state.album
        npColArtist.visibility = if (hasArtist) View.VISIBLE else View.GONE
        npColAlbum.visibility = if (hasAlbum) View.VISIBLE else View.GONE
        npMeta.visibility = if (hasArtist || hasAlbum) View.VISIBLE else View.GONE
        // Source pill shows the device name; fall back to the "AirPlay · …" caption.
        val source = state.senderName.ifBlank { state.caption }
        npCaption.text = source
        npSourcePill.visibility = if (source.isNotBlank()) View.VISIBLE else View.GONE
        if (state.artwork !== shownArtwork) loadArtwork(state.artwork)
        renderPlaying(state.playing)
        btnPlayPause.setImageResource(if (state.playing) R.drawable.ic_pause else R.drawable.ic_play)
        controls.alpha = if (state.controlsEnabled) 1f else 0.4f
        if (state.mode == PlayerMode.AUDIO && !controlsFocused) { controlsFocused = btnPlayPause.requestFocus() }

        main.removeCallbacks(ticker)
        main.post(ticker)

        videoFrame.setAspect(state.videoWidth, state.videoHeight)
        // Low-latency "game" picture mode (ALLM) on TVs that support it, only while mirroring.
        if (Build.VERSION.SDK_INT >= 30) window.setPreferMinimalPostProcessing(state.gameMode)
    }

    private val applyPause = Runnable { applyPlaying(false) }

    /**
     * Apple Music behaviour: artwork shrinks back when paused and springs forward on play.
     * Play shows instantly; pause waits 300 ms so a seek (flush + immediate resume) doesn't flicker.
     */
    private fun renderPlaying(playing: Boolean) {
        main.removeCallbacks(applyPause)
        if (playing) applyPlaying(true)
        else if (shownPlaying != false) main.postDelayed(applyPause, 300)
    }

    private fun applyPlaying(playing: Boolean) {
        ambient.playing = playing
        if (shownPlaying == playing) return
        val first = shownPlaying == null
        shownPlaying = playing
        // Apple Music: paused artwork simply shrinks (no dimming). Resume springs back with a slight overshoot.
        artwork.animate()
            .scaleX(if (playing) 1f else 0.9f).scaleY(if (playing) 1f else 0.9f)
            .setInterpolator(if (playing) android.view.animation.OvershootInterpolator(1.2f) else Motion.standard)
            .setDuration(if (first || !Motion.enabled) 0 else if (playing) 450 else 350)
            .withLayer()   // scale a cached texture of the rounded artwork instead of re-clipping each frame
            .start()
    }

    private fun renderProgress() {
        val p = latest.progress
        if (p == null || p.durationMs <= 0) { npProgressGroup.visibility = View.INVISIBLE; return }
        npProgressGroup.visibility = View.VISIBLE
        val now = SystemClock.elapsedRealtime()
        val pos = p.positionMsAt(now)
        progressLine.fraction = p.fractionAt(now)
        npElapsed.text = TimeText.elapsed(pos)
        npRemaining.text = TimeText.remaining(pos, p.durationMs)
    }

    /** Decode, blur and extract colours off the main thread, then apply with a crossfade. */
    private fun loadArtwork(art: Artwork?) {
        shownArtwork = art
        if (art == null) { showPlaceholderArtwork(); return }
        decoder.execute {
            val bmp = BitmapFactory.decodeByteArray(art.bytes, 0, art.bytes.size) ?: return@execute
            val small = Bitmap.createScaledBitmap(bmp, 32, 32, true)
            val pixels = IntArray(32 * 32).also { small.getPixels(it, 0, 32, 0, 0, 32, 32) }
            val palette = ArtworkPalette.extract(pixels)
            val ambientColors = ArtworkPalette.ambient(palette)
            val accent = ArtworkPalette.accent(palette)
            val blurred = AmbientBackgroundView.blur(bmp)
            main.post {
                if (shownArtwork !== art) return@post   // a newer track arrived meanwhile
                artworkLoaded = true
                artwork.setImageBitmap(bmp)
                artwork.scaleType = ImageView.ScaleType.CENTER_CROP
                artwork.alpha = if (latest.playing) 1f else 0.85f
                ambient.setArtwork(ambientColors, blurred)
                progressLine.accent = accent
            }
        }
    }

    private fun showPlaceholderArtwork() {
        artworkLoaded = false
        artwork.setImageResource(R.drawable.ic_music_note)
        artwork.scaleType = ImageView.ScaleType.CENTER
        artwork.imageTintList = null
        ambient.setArtwork(ArtworkPalette.ambient(emptyList()), null)
        progressLine.accent = getColor(R.color.np_text)
    }

    override fun handle(effect: PlayerEffect) {
        when (effect) {
            is PlayerEffect.Hint -> message.show(effect.text, 2000)
            PlayerEffect.Finish -> finish()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) { viewModel.onAction(PlayerAction.BackPressed); return true }
        // Remote media keys control the sender's playback, like on Apple TV.
        val command = when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> RemoteCommand.PLAY_PAUSE
            KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> RemoteCommand.NEXT
            KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_REWIND -> RemoteCommand.PREVIOUS
            else -> null
        }
        if (command != null && latest.mode == PlayerMode.AUDIO) {
            if (event.repeatCount == 0) viewModel.onAction(PlayerAction.Remote(command))
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun bindControl(button: ImageButton, command: RemoteCommand) {
        button.setOnClickListener { viewModel.onAction(PlayerAction.Remote(command)) }
        // Same focus as the settings rows (SettingRowView): glass lights up to silver (drawable
        // state), then the same lift and spring. Slightly more scale since the targets are small.
        val lift = 16 * resources.displayMetrics.density
        button.setOnFocusChangeListener { v, focused ->
            val s = if (focused) 1.08f else 1f
            v.animate().scaleX(s).scaleY(s).translationZ(if (focused) lift else 0f)
                .setInterpolator(if (focused) Motion.focusIn else Motion.focusOut)
                .setDuration(if (focused) Motion.focusInMs() else Motion.focusOutMs()).start()
        }
    }

    @Deprecated("Back is handled in onKeyDown")
    override fun onBackPressed() = viewModel.onAction(PlayerAction.BackPressed)

    // ---- SurfaceHolder.Callback ----
    override fun surfaceCreated(holder: SurfaceHolder) =
        viewModel.onAction(PlayerAction.SurfaceAvailable(container.wrapSurface(holder.surface)))

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
    override fun surfaceDestroyed(holder: SurfaceHolder) = viewModel.onAction(PlayerAction.SurfaceGone)

    private fun matchParent() = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)

    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
    }
}
