package dev.better.airplay.presentation.components

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Apple-Music/tvOS-style living background: soft colour fields taken from the artwork drift slowly
 * over a deep base, with the blurred artwork underneath and a scrim for text contrast.
 * Motion pauses with playback and is disabled entirely when system animations are off.
 */
class AmbientBackgroundView(context: Context) : View(context) {

    private val argb = ArgbEvaluator()
    private var colors = IntArray(BLOBS) { 0xFF1D2024.toInt() }
    private var fromColors = colors.copyOf()
    private var toColors = colors.copyOf()
    private var blend = 1f
    private var blendAnimator: ValueAnimator? = null

    private var blurred: Bitmap? = null
    private val blurPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = 46 }   // ~18%: texture, not a picture
    private val blobPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val basePaint = Paint()
    private val scrimPaint = Paint()
    private val matrix = Matrix()
    private val dst = Rect()

    var playing = true
        set(value) { field = value; if (value) postInvalidateOnAnimation() }

    // Each blob: orbit centre (fraction of size), orbit radius, angular speed (rad/s), phase, size factor.
    private val blobs = arrayOf(
        // Large, diagonal fields; ~25-40 s loops, +/-8% drift (tvOS-like: calm, never pulsing).
        floatArrayOf(0.15f, 0.20f, 0.08f, 0.033f, 0.0f, 1.10f),
        floatArrayOf(0.85f, 0.15f, 0.08f, 0.025f, 1.7f, 0.95f),
        floatArrayOf(0.30f, 0.90f, 0.08f, 0.029f, 3.1f, 1.05f),
        floatArrayOf(0.72f, 0.62f, 0.08f, 0.040f, 4.4f, 0.80f),   // highlight field, right-centre
    )
    private var timeSec = 0f
    private var lastFrameMs = 0L
    private val shaders = arrayOfNulls<RadialGradient>(BLOBS)
    private val shaderColors = IntArray(BLOBS)
    private val shaderRadius = FloatArray(BLOBS)

    /** New palette (most dominant first) and pre-blurred artwork; crossfades from the current look. */
    fun setArtwork(palette: List<Int>, blurredArtwork: Bitmap?) {
        blurred = blurredArtwork
        fromColors = currentColors()
        toColors = IntArray(BLOBS) { i -> palette.getOrElse(i % palette.size.coerceAtLeast(1)) { 0xFF1D2024.toInt() } }
        blendAnimator?.cancel()
        blendAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (Motion.enabled) 900 else 0
            addUpdateListener { blend = it.animatedValue as Float; colors = currentColors(); invalidate() }
            start()
        }
    }

    private fun currentColors() = IntArray(BLOBS) { argb.evaluate(blend, fromColors[it], toColors[it]) as Int }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        // Uniform 18% dim plus a vignette to ~40% at the corners keeps text legible on any palette.
        scrimPaint.shader = RadialGradient(w / 2f, h / 2f, maxOf(w, h) * 0.75f,
            intArrayOf(0x2E000000, 0x2E000000, 0x66000000), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val now = SystemClock.uptimeMillis()
        if (lastFrameMs != 0L && playing && Motion.enabled) timeSec += (now - lastFrameMs).coerceAtMost(100) / 1000f
        lastFrameMs = now

        // Deep base tinted by the dominant colour.
        basePaint.color = darken(colors[0], 0.35f)
        canvas.drawRect(0f, 0f, w, h, basePaint)

        // Blurred artwork, low opacity, for texture.
        blurred?.let { dst.set(0, 0, width, height); canvas.drawBitmap(it, null, dst, blurPaint) }

        // Drifting colour fields.
        val r = max(w, h)
        for (i in 0 until BLOBS) {
            val b = blobs[i]
            val angle = timeSec * b[3] * 6.2832f + b[4]
            val cx = (b[0] + b[2] * cos(angle)) * w
            val cy = (b[1] + b[2] * sin(angle * 0.8f)) * h
            val radius = r * 0.55f * b[5]
            // Rebuild a blob's shader only when its colour or size changes; move it with a matrix per frame.
            if (shaders[i] == null || shaderColors[i] != colors[i] || shaderRadius[i] != radius) {
                shaders[i] = RadialGradient(0f, 0f, radius,
                    intArrayOf(withAlpha(colors[i], 0xD0), withAlpha(colors[i], 0x50), 0), floatArrayOf(0f, 0.55f, 1f),
                    Shader.TileMode.CLAMP)
                shaderColors[i] = colors[i]; shaderRadius[i] = radius
            }
            matrix.setTranslate(cx, cy)
            shaders[i]!!.setLocalMatrix(matrix)
            blobPaint.shader = shaders[i]
            canvas.drawCircle(cx, cy, radius, blobPaint)
        }

        canvas.drawRect(0f, 0f, w, h, scrimPaint)
        if (playing && Motion.enabled) postInvalidateOnAnimation()
    }

    companion object {
        private const val BLOBS = 4

        /** Cheap, good-looking blur for a background: downscale hard, then upscale with filtering twice. */
        fun blur(src: Bitmap): Bitmap {
            val tiny = Bitmap.createScaledBitmap(src, 16, 16, true)
            val mid = Bitmap.createScaledBitmap(tiny, 64, 64, true)
            return Bitmap.createScaledBitmap(mid, 256, 256, true)
        }

        private fun withAlpha(c: Int, a: Int) = (c and 0x00FFFFFF) or (a shl 24)
        private fun darken(c: Int, f: Float): Int {
            val r = (((c shr 16) and 0xFF) * f).toInt(); val g = (((c shr 8) and 0xFF) * f).toInt(); val b = ((c and 0xFF) * f).toInt()
            return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
    }
}
