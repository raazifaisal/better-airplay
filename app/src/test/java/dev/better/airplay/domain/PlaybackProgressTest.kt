package dev.better.airplay.domain

import dev.better.airplay.data.engine.NativeEventParser
import dev.better.airplay.domain.model.PlaybackProgress
import dev.better.airplay.domain.model.ReceiverStatus
import dev.better.airplay.domain.model.Sender
import dev.better.airplay.domain.port.EngineEvent
import dev.better.airplay.domain.usecase.TrackSession
import dev.better.airplay.presentation.player.TimeText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackProgressTest {
    // Real values captured from an iPhone playing a 4:05 song on this TV.
    private val p = PlaybackProgress(startRtp = 1076866849, currentRtp = 1077754856, endRtp = 1087655821, receivedAtMs = 10_000)

    @Test fun `converts RTP sample positions at 44_1 kHz`() {
        assertEquals(20_136, p.positionMsAt(10_000))      // 888_007 samples
        assertEquals(244_647, p.durationMs)               // 10_788_972 samples = 244_647.9 ms, truncated
    }

    @Test fun `advances with the clock while playing`() {
        assertEquals(25_136, p.positionMsAt(15_000))
    }

    @Test fun `never runs past the end`() {
        assertEquals(p.durationMs, p.positionMsAt(10_000_000))
    }

    @Test fun `freezes while paused`() {
        val paused = p.copy(playing = false)
        assertEquals(20_136, paused.positionMsAt(60_000))
    }

    @Test fun `handles 32-bit RTP wraparound`() {
        // start + 44_100 samples wraps past 2^32: 0xFFFFFF00 + 0xAC44 = 0x1_0000_AB44
        val w = PlaybackProgress(startRtp = 0xFFFF_FF00L, currentRtp = 0x0000_0100L, endRtp = 0x0000_AB44L, receivedAtMs = 0)
        assertEquals(11, w.positionMsAt(0))         // 512 samples
        assertEquals(1_000, w.durationMs)           // exactly 44_100 samples
    }

    @Test fun `fraction is clamped to 0_1`() {
        assertEquals(0f, PlaybackProgress(5, 5, 5, 0).fractionAt(0), 0f)
        assertEquals(1f, p.fractionAt(10_000_000), 0f)
    }
}

class ProgressEventsTest {
    @Test fun `native progress event parses into ProgressChanged`() {
        val e = NativeEventParser.parse("progress", "1076866849/1077754856/1087655821", nowMs = 42) as EngineEvent.ProgressChanged
        assertEquals(PlaybackProgress(1076866849, 1077754856, 1087655821, 42), e.progress)
    }

    @Test fun `malformed progress is ignored`() {
        assertNull(NativeEventParser.parse("progress", "nope", nowMs = 0))
    }

    @Test fun `flush pauses progress, a new progress update resumes it, session end clears it`() {
        val store = StatusStore()
        val track = TrackSession(store)
        track.onEngineEvent(EngineEvent.SenderConnecting(Sender("iPhone")))
        track.onEngineEvent(EngineEvent.AudioStreamStarted)
        track.onEngineEvent(EngineEvent.ProgressChanged(PlaybackProgress(0, 44_100, 441_000, 0)))
        assertTrue(store.snapshot.progress!!.playing)

        track.onEngineEvent(EngineEvent.AudioFlushed(nowMs = 2_000))
        val paused = store.snapshot.progress!!
        assertFalse(paused.playing)
        assertEquals(3_000, paused.positionMsAt(99_000))   // frozen at the flush moment

        track.onEngineEvent(EngineEvent.ProgressChanged(PlaybackProgress(0, 44_100 * 3, 441_000, 5_000)))
        assertTrue(store.snapshot.progress!!.playing)

        track.onEngineEvent(EngineEvent.SessionEnded)
        assertNull(store.snapshot.progress)
        assertTrue(store.snapshot.status !is ReceiverStatus.PlayingAudio)
    }
}

/**
 * Established approach (shairport-sync): `prgr` start/current/end are anchors; the live position is the RTP
 * timestamp of the frame actually being played ("FramePosition"), relative to start, modulo 2^32.
 */
class PlayheadTest {
    private val store = StatusStore()
    private val track = TrackSession(store)

    private fun music() {
        track.onEngineEvent(EngineEvent.SenderConnecting(Sender("iPhone")))
        track.onEngineEvent(EngineEvent.AudioStreamStarted)
        // 4:00 track, 1:00 in (real-style RTP values)
        track.onEngineEvent(EngineEvent.ProgressChanged(PlaybackProgress(2_672_729_130, 2_672_729_130 + 44_100 * 60, 2_672_729_130 + 44_100 * 240, 0)))
    }

    @Test fun `progress whose current lies outside start-end is ignored (captured from a real iPhone after resume)`() {
        music()
        track.onEngineEvent(EngineEvent.ProgressChanged(PlaybackProgress(217_986_997, 246_575_933, 232_859_013, 5_000)))
        assertEquals(60_000, store.snapshot.progress!!.positionMsAt(0))
    }

    @Test fun `the playing frame's RTP drives the position`() {
        music()
        track.onEngineEvent(EngineEvent.Playhead(rtp = 2_672_729_130 + 44_100 * 75, nowMs = 9_000))
        assertEquals(75_000, store.snapshot.progress!!.positionMsAt(9_000))
        assertEquals(75_500, store.snapshot.progress!!.positionMsAt(9_500))   // still extrapolates between frames
    }

    @Test fun `a playhead from another play sequence is ignored`() {
        music()
        track.onEngineEvent(EngineEvent.Playhead(rtp = 100, nowMs = 9_000))
        assertEquals(60_000, store.snapshot.progress!!.positionMsAt(0))
    }

    @Test fun `native playhead event parses`() {
        assertEquals(EngineEvent.Playhead(3_000_000_000, 7), NativeEventParser.parse("audio_rtp", "3000000000", nowMs = 7))
    }
}

class TimeTextTest {
    @Test fun `formats elapsed and remaining like tvOS`() {
        assertEquals("0:00", TimeText.elapsed(0))
        assertEquals("1:05", TimeText.elapsed(65_400))
        assertEquals("1:02:03", TimeText.elapsed(3_723_000))
        assertEquals("−3:20", TimeText.remaining(5_000, 205_000))   // Apple uses a true minus sign
        assertEquals("−0:00", TimeText.remaining(10_000, 9_000))
    }
}
