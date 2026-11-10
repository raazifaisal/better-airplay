package dev.better.airplay.presentation.player

import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Pure colour extraction for the Apple-Music-style ambient background: quantize artwork pixels,
 * rank swatches by coverage, merge near-duplicates. Works on raw ARGB ints so it is unit-testable.
 */
object ArtworkPalette {
    private val FALLBACK = listOf(0xFF2A3550.toInt(), 0xFF1D2024.toInt(), 0xFF3E4759.toInt())

    fun extract(pixels: IntArray, maxColors: Int = 5): List<Int> {
        class Bucket { var n = 0; var r = 0L; var g = 0L; var b = 0L }
        val buckets = HashMap<Int, Bucket>()
        for (p in pixels) {
            if ((p ushr 24) < 128) continue
            val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
            val key = ((r shr 4) shl 8) or ((g shr 4) shl 4) or (b shr 4)
            val bk = buckets.getOrPut(key) { Bucket() }
            bk.n++; bk.r += r; bk.g += g; bk.b += b
        }
        if (buckets.isEmpty()) return FALLBACK
        val ranked = buckets.values.sortedByDescending { it.n }
            .map { argb((it.r / it.n).toInt(), (it.g / it.n).toInt(), (it.b / it.n).toInt()) to it.n }
        val out = ArrayList<Int>()
        for ((color, _) in ranked) {
            if (out.none { distance(it, color) < 28.0 }) out += color
            if (out.size == maxColors) break
        }
        return out
    }

    /**
     * Background colours, tvOS-style: dark, slightly richer swatches (HSL lightness 18-38%, saturation x1.1),
     * with the last one allowed up to 55% as a single highlight field. Always returns [count] colours.
     */
    fun ambient(palette: List<Int>, count: Int = 4): List<Int> {
        val src = palette.ifEmpty { FALLBACK }
        return List(count) { i ->
            val (h, s, l) = toHsl(src[i % src.size])
            val maxL = if (i == count - 1) 0.55 else 0.38
            // Aim slightly inside the band so 8-bit rounding can't push a colour past its edge.
            fromHsl(h, (s * 1.1).coerceAtMost(1.0), l.coerceIn(0.185, maxL - 0.005))
        }
    }

    /** HSL lightness 0..1. */
    fun lightness(c: Int): Double = toHsl(c).third

    private fun toHsl(c: Int): Triple<Double, Double, Double> {
        val r = ((c shr 16) and 0xFF) / 255.0; val g = ((c shr 8) and 0xFF) / 255.0; val b = (c and 0xFF) / 255.0
        val max = maxOf(r, g, b); val min = minOf(r, g, b); val l = (max + min) / 2
        if (max == min) return Triple(0.0, 0.0, l)
        val d = max - min
        val s = if (l > 0.5) d / (2 - max - min) else d / (max + min)
        val h = when (max) { r -> ((g - b) / d + (if (g < b) 6 else 0)); g -> (b - r) / d + 2; else -> (r - g) / d + 4 } / 6
        return Triple(h, s, l)
    }

    private fun fromHsl(h: Double, s: Double, l: Double): Int {
        if (s == 0.0) { val v = (l * 255).roundToInt(); return argb(v, v, v) }
        val q = if (l < 0.5) l * (1 + s) else l + s - l * s
        val p = 2 * l - q
        fun hue(t0: Double): Double {
            var t = t0; if (t < 0) t += 1.0; if (t > 1) t -= 1.0
            return when { t < 1.0 / 6 -> p + (q - p) * 6 * t; t < 0.5 -> q; t < 2.0 / 3 -> p + (q - p) * (2.0 / 3 - t) * 6; else -> p }
        }
        return argb((hue(h + 1.0 / 3) * 255).roundToInt().coerceIn(0, 255), (hue(h) * 255).roundToInt().coerceIn(0, 255),
            (hue(h - 1.0 / 3) * 255).roundToInt().coerceIn(0, 255))
    }

    /** The most colourful swatch, lightened until it reads clearly on a dark background. */
    fun accent(colors: List<Int>): Int {
        var c = colors.maxByOrNull { saturation(it) * 0.7 + luminance(it) * 0.3 } ?: FALLBACK.first()
        var guard = 0
        while (luminance(c) < 0.35 && guard++ < 12) c = mix(c, 0xFFFFFFFF.toInt(), 0.18f)
        return c
    }

    /** WCAG relative luminance, 0..1. */
    fun luminance(c: Int): Double {
        fun ch(v: Int) = (v / 255.0).let { if (it <= 0.03928) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4) }
        return 0.2126 * ch((c shr 16) and 0xFF) + 0.7152 * ch((c shr 8) and 0xFF) + 0.0722 * ch(c and 0xFF)
    }

    fun mix(a: Int, b: Int, t: Float): Int {
        fun m(x: Int, y: Int) = (x + (y - x) * t).toInt().coerceIn(0, 255)
        return argb(m((a shr 16) and 0xFF, (b shr 16) and 0xFF), m((a shr 8) and 0xFF, (b shr 8) and 0xFF), m(a and 0xFF, b and 0xFF))
    }

    private fun saturation(c: Int): Double {
        val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
        val max = maxOf(r, g, b); val min = minOf(r, g, b)
        return if (max == 0) 0.0 else (max - min).toDouble() / max
    }

    private fun distance(a: Int, b: Int): Double {
        val dr = ((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)
        val dg = ((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)
        val db = (a and 0xFF) - (b and 0xFF)
        return sqrt((dr * dr + dg * dg + db * db).toDouble())
    }

    private fun argb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
}
