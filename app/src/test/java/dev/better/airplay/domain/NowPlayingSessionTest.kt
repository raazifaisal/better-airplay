package dev.better.airplay.domain

import dev.better.airplay.data.engine.NativeEventParser
import dev.better.airplay.domain.model.Artwork
import dev.better.airplay.domain.model.ArtworkCache
import dev.better.airplay.domain.model.NowPlaying
import dev.better.airplay.domain.model.PlaybackProgress
import dev.better.airplay.domain.model.ReceiverStatus
import dev.better.airplay.domain.model.Sender
import dev.better.airplay.domain.port.EngineEvent
import dev.better.airplay.domain.usecase.TrackSession
import dev.better.airplay.presentation.player.ArtworkPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** iOS pauses AirPlay audio by tearing down the audio stream while keeping the connection. */
class NowPlayingSessionTest {
    private val store = StatusStore()
    private val track = TrackSession(store)
    private val song = NowPlaying("Deewaana Deewaana", "A.R. Rahman", "Tere Ishk Mein")
    private val art = Artwork(byteArrayOf(1, 2, 3))

    /** Event order captured from a real iPhone (iOS 26/27): artwork FIRST, then metadata, then a shorter metadata. */
    private fun startMusic() {
        track.onEngineEvent(EngineEvent.SenderConnecting(Sender("Raazi’s iPhone")))
        track.onEngineEvent(EngineEvent.AudioStreamStarted)
        track.onEngineEvent(EngineEvent.ArtworkChanged(art))
        track.onEngineEvent(EngineEvent.ProgressChanged(PlaybackProgress(0, 44_100 * 10, 44_100 * 200, receivedAtMs = 0)))
        track.onEngineEvent(EngineEvent.NowPlayingChanged(song))
    }

    @Test fun `artwork sent before metadata is kept (real iOS order)`() {
        startMusic()
        assertSame(art, store.snapshot.artwork)
        assertEquals(song, store.snapshot.nowPlaying)
    }

    @Test fun `a shorter follow-up metadata for the same song keeps artwork and merges fields`() {
        startMusic()
        track.onEngineEvent(EngineEvent.NowPlayingChanged(NowPlaying(song.title, song.artist, album = null)))
        assertSame(art, store.snapshot.artwork)
        assertEquals("Tere Ishk Mein", store.snapshot.nowPlaying?.album)
    }

    @Test fun `next track - its artwork arrives first, then its metadata`() {
        startMusic()
        val art2 = Artwork(byteArrayOf(9))
        track.onEngineEvent(EngineEvent.ArtworkChanged(art2))
        track.onEngineEvent(EngineEvent.NowPlayingChanged(NowPlaying("Next Song", "Someone", "Else")))
        assertSame(art2, store.snapshot.artwork)
        // skipping back shows the first song's art from cache, without iOS resending it
        track.onEngineEvent(EngineEvent.NowPlayingChanged(song))
        assertSame(art, store.snapshot.artwork)
    }

    @Test fun `stream teardown is a pause - metadata and artwork stay, progress freezes`() {
        startMusic()
        track.onEngineEvent(EngineEvent.AudioStreamStopped(nowMs = 5_000))
        val s = store.snapshot
        assertTrue(s.status is ReceiverStatus.PlayingAudio)
        assertFalse(s.playing)
        assertEquals(song, s.nowPlaying)
        assertSame(art, s.artwork)
        assertEquals(15_000, s.progress!!.positionMsAt(60_000))
    }

    @Test fun `a new audio stream resumes the same session`() {
        startMusic()
        track.onEngineEvent(EngineEvent.AudioStreamStopped(nowMs = 5_000))
        track.onEngineEvent(EngineEvent.AudioStreamStarted)
        assertTrue(store.snapshot.playing)
        assertEquals(song, store.snapshot.nowPlaying)
        assertSame(art, store.snapshot.artwork)
    }

    @Test fun `a different track drops the old artwork until its own arrives`() {
        startMusic()
        track.onEngineEvent(EngineEvent.NowPlayingChanged(NowPlaying("Next Song", "Someone", "Else")))
        assertNull(store.snapshot.artwork)
    }

    @Test fun `going back to a cached track restores its artwork instantly`() {
        startMusic()
        track.onEngineEvent(EngineEvent.NowPlayingChanged(NowPlaying("Next Song", "Someone", "Else")))
        track.onEngineEvent(EngineEvent.NowPlayingChanged(song))
        assertSame(art, store.snapshot.artwork)
    }

    @Test fun `identical metadata resent by iOS keeps the artwork`() {
        startMusic()
        track.onEngineEvent(EngineEvent.NowPlayingChanged(song.copy()))
        assertSame(art, store.snapshot.artwork)
    }

    @Test fun `flush pauses immediately - iOS tears the stream down only seconds later`() {
        startMusic()
        track.onEngineEvent(EngineEvent.AudioFlushed(nowMs = 4_000))
        assertFalse(store.snapshot.playing)
        assertEquals(14_000, store.snapshot.progress!!.positionMsAt(30_000))
    }

    @Test fun `audio resuming continues from the frozen position`() {
        startMusic()
        track.onEngineEvent(EngineEvent.AudioFlushed(nowMs = 4_000))
        track.onEngineEvent(EngineEvent.AudioResumed(nowMs = 9_000))
        assertTrue(store.snapshot.playing)
        assertEquals(15_000, store.snapshot.progress!!.positionMsAt(10_000))   // 14s frozen + 1s since resume
    }

    @Test fun `native pause and resume signals map to events`() {
        assertEquals(EngineEvent.AudioFlushed(3), NativeEventParser.parse("audio_idle", "", nowMs = 3))
        assertEquals(EngineEvent.AudioResumed(5), NativeEventParser.parse("audio_resumed", "", nowMs = 5))
    }

    @Test fun `the stream-stopped native event maps to AudioStreamStopped`() {
        assertEquals(EngineEvent.AudioStreamStopped(7), NativeEventParser.parse("audio_stream_stopped", "", nowMs = 7))
    }
}

class ArtworkCacheTest {
    @Test fun `evicts least recently used beyond capacity`() {
        val cache = ArtworkCache(capacity = 2)
        val a = Artwork(byteArrayOf(1)); val b = Artwork(byteArrayOf(2)); val c = Artwork(byteArrayOf(3))
        cache.put("a", a); cache.put("b", b)
        assertSame(a, cache.get("a"))   // touch a, so b is now least recent
        cache.put("c", c)
        assertNull(cache.get("b")); assertSame(a, cache.get("a")); assertSame(c, cache.get("c"))
    }
}

class ArtworkPaletteTest {
    private fun image(vararg colorsAndCounts: Pair<Int, Int>) =
        colorsAndCounts.flatMap { (c, n) -> List(n) { c } }.toIntArray()

    @Test fun `dominant color of a mostly-red image is red`() {
        val colors = ArtworkPalette.extract(image(0xFFE53935.toInt() to 900, 0xFF1E88E5.toInt() to 100))
        assertEquals(0xFFE53935.toInt(), colors.first())
    }

    @Test fun `returns distinct colors ordered by coverage`() {
        val colors = ArtworkPalette.extract(image(0xFF1E88E5.toInt() to 600, 0xFFFDD835.toInt() to 300, 0xFF43A047.toInt() to 100))
        assertEquals(listOf(0xFF1E88E5.toInt(), 0xFFFDD835.toInt(), 0xFF43A047.toInt()), colors.take(3))
    }

    @Test fun `near-identical shades merge into one swatch`() {
        val colors = ArtworkPalette.extract(image(0xFFE53935.toInt() to 500, 0xFFE63A36.toInt() to 500))
        assertEquals(1, colors.size)
    }

    @Test fun `empty or fully transparent input falls back to a neutral palette`() {
        assertTrue(ArtworkPalette.extract(IntArray(0)).isNotEmpty())
        assertTrue(ArtworkPalette.extract(IntArray(10) { 0x00000000 }).isNotEmpty())
    }

    @Test fun `ambient colours stay dark and moody like tvOS, with one brighter highlight`() {
        val ambient = ArtworkPalette.ambient(listOf(0xFFFFFFFF.toInt(), 0xFFFFEB3B.toInt(), 0xFF000000.toInt(), 0xFF2196F3.toInt()))
        assertEquals(4, ambient.size)
        ambient.take(3).forEach { assertTrue(ArtworkPalette.lightness(it) in 0.18..0.38 + 1e-6) }
        assertTrue(ArtworkPalette.lightness(ambient[3]) in 0.18..0.55 + 1e-6)
    }

    @Test fun `accent for the progress bar stays readable on a dark background`() {
        val accent = ArtworkPalette.accent(listOf(0xFF101010.toInt()))   // near-black artwork
        assertTrue(ArtworkPalette.luminance(accent) > 0.35)
    }
}
