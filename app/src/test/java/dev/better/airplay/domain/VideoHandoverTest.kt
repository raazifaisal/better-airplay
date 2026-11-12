package dev.better.airplay.domain

import dev.better.airplay.domain.model.ReceiverStatus
import dev.better.airplay.domain.model.Sender
import dev.better.airplay.domain.model.VideoError
import dev.better.airplay.domain.model.VideoPlaybackInfo
import dev.better.airplay.domain.port.EngineEvent
import dev.better.airplay.domain.port.VideoSurface
import dev.better.airplay.domain.usecase.EngineEventFanout
import dev.better.airplay.domain.usecase.TrackSession
import dev.better.airplay.domain.usecase.VideoHandoverSession
import dev.better.airplay.domain.usecase.VideoSurfaceRouter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoPlaybackInfoTest {
    private fun info(
        durationMs: Long = 600_000, positionMs: Long = 30_000, prepared: Boolean = true,
        playIntent: Boolean = true, buffering: Boolean = false, completed: Boolean = false,
    ) = VideoPlaybackInfo.fromPlayer(durationMs, positionMs, prepared, playIntent, buffering, completed)

    @Test fun `playing video reports seconds, rate 1 and a full buffer`() {
        val i = info()
        assertEquals(600.0, i.durationSec, 1e-9); assertEquals(30.0, i.positionSec, 1e-9)
        assertEquals(1f, i.rate); assertTrue(i.ready); assertTrue(i.bufferFull); assertFalse(i.bufferEmpty); assertFalse(i.ended)
    }

    @Test fun `paused video reports rate 0`() {
        assertEquals(0f, info(playIntent = false).rate)
    }

    @Test fun `stalled video keeps rate 1 like AVPlayer but reports an empty buffer`() {
        val i = info(buffering = true)
        assertEquals(1f, i.rate); assertTrue(i.bufferEmpty); assertFalse(i.bufferFull)
    }

    @Test fun `before prepare nothing is ready`() {
        val i = info(prepared = false, durationMs = -1, positionMs = 0)
        assertFalse(i.ready); assertEquals(0.0, i.durationSec, 0.0); assertEquals(0f, i.rate)
    }

    @Test fun `unknown or live duration is 0 and position is never negative or past the end`() {
        assertEquals(0.0, info(durationMs = -1).durationSec, 0.0)
        assertEquals(0.0, info(positionMs = -500).positionSec, 0.0)
        assertEquals(600.0, info(positionMs = 700_000).positionSec, 1e-9)
    }

    @Test fun `completion is reported as ended at the end with rate 0`() {
        val i = info(completed = true, positionMs = 599_000)
        assertTrue(i.ended); assertEquals(600.0, i.positionSec, 1e-9); assertEquals(0f, i.rate)
    }
}

private class Surface(val name: String) : VideoSurface { override fun toString() = name }

class VideoSurfaceRouterTest {
    private val engine = FakeEngine()
    private val player = FakeVideoPlayer()
    private val router = VideoSurfaceRouter(engine, player)
    private val s = Surface("main")

    @Test fun `the screen goes to mirroring by default`() {
        router.attach(s)
        assertSame(s, engine.surface); assertNull(player.attached)
    }

    @Test fun `during a handover the screen moves to the video player and back afterwards`() {
        router.attach(s)
        router.setHandoverActive(true)
        assertNull(engine.surface); assertSame(s, player.attached)
        router.setHandoverActive(false)
        assertSame(s, engine.surface); assertNull(player.attached)
    }

    @Test fun `the old owner lets go before the new owner takes the surface`() {
        val log = mutableListOf<String>()
        engine.log = log; player.log = log
        router.attach(s)
        router.setHandoverActive(true)
        router.setHandoverActive(false)
        // A Surface can only have one producer: the current owner detaches before the next attaches.
        assertEquals(
            listOf("engine surface=main", "engine surface=null", "player surface=main",
                "player surface=null", "engine surface=main"),
            log,
        )
    }

    @Test fun `a surface that arrives mid-handover goes straight to the player, and detaching clears it`() {
        router.setHandoverActive(true)
        router.attach(s)
        assertSame(s, player.attached); assertNull(engine.surface)
        router.attach(null)
        assertNull(player.attached)
    }
}

class VideoHandoverSessionTest {
    private val store = StatusStore().apply { update { it.copy(status = ReceiverStatus.Ready(7000)) } }
    private val engine = FakeEngine()
    private val player = FakeVideoPlayer()
    private val router = VideoSurfaceRouter(engine, player)
    private val session = VideoHandoverSession(engine, player, router, store)
    private val events = EngineEventFanout(listOf(TrackSession(store), session))
    private val phone = Sender("Raazi's iPhone")
    private val url = "http://localhost:7000/master.m3u8"

    private fun start(at: Double = 0.0) {
        router.attach(Surface("tv"))
        events.onEngineEvent(EngineEvent.SenderConnecting(phone))
        events.onEngineEvent(EngineEvent.VideoHandoverStarted(url, at))
    }

    @Test fun `handover plays the URL from the start position on the TV's screen`() {
        start(at = 95.0)
        assertEquals(listOf("play $url @95.0"), player.calls)
        assertTrue(store.snapshot.status is ReceiverStatus.PlayingVideo)
        assertEquals(phone, store.snapshot.status.currentSender)
        assertTrue(player.attached != null); assertNull(engine.surface)
    }

    @Test fun `the phone's scrubber, pause and play drive the player`() {
        start()
        events.onEngineEvent(EngineEvent.VideoHandoverSeek(120.0))
        events.onEngineEvent(EngineEvent.VideoHandoverRate(0f))
        events.onEngineEvent(EngineEvent.VideoHandoverRate(1f))
        assertEquals(listOf("seek 120.0", "pause", "resume"), player.calls.drop(1))
    }

    @Test fun `player progress is relayed to the engine so the phone can track it`() {
        start()
        val i = VideoPlaybackInfo.fromPlayer(60_000, 5_000, true, true, false, false)
        player.listener!!.onInfo(i)
        assertEquals(i, engine.playbackInfo.last())
    }

    @Test fun `a new handover first resets the engine's info so stale progress isn't reported`() {
        start()
        assertEquals(VideoPlaybackInfo.IDLE, engine.playbackInfo.first())
    }

    @Test fun `video size from the player reaches the screen for aspect-correct display`() {
        start()
        player.listener!!.onVideoSize(1920, 800)
        assertEquals(1920, store.snapshot.videoWidth); assertEquals(800, store.snapshot.videoHeight)
    }

    @Test fun `stop from the phone stops the player, frees the screen and returns to Ready`() {
        start()
        events.onEngineEvent(EngineEvent.VideoHandoverStopped)
        assertEquals("stop", player.calls.last())
        assertTrue(engine.surface != null); assertNull(player.attached)
        assertEquals(ReceiverStatus.Ready(7000), store.snapshot.status)
    }

    @Test fun `the session ending (phone gone) also stops playback`() {
        start()
        events.onEngineEvent(EngineEvent.SessionEnded)
        assertEquals("stop", player.calls.last())
    }

    @Test fun `stop without a handover does nothing to the player`() {
        events.onEngineEvent(EngineEvent.SessionEnded)
        assertTrue(player.calls.isEmpty())
    }

    @Test fun `info after the handover ended is not relayed`() {
        start()
        events.onEngineEvent(EngineEvent.VideoHandoverStopped)
        val before = engine.playbackInfo.size
        player.listener!!.onInfo(VideoPlaybackInfo.fromPlayer(60_000, 5_000, true, true, false, false))
        assertEquals(before, engine.playbackInfo.size)
    }

    @Test fun `a playback error releases the phone by reporting the video as ended`() {
        start()
        player.listener!!.onError(VideoError(1, -1004))
        assertTrue(engine.playbackInfo.last().ended)
        assertEquals("stop", player.calls.last())
    }

    @Test fun `seeks and rate changes outside a handover are ignored`() {
        events.onEngineEvent(EngineEvent.VideoHandoverSeek(3.0))
        events.onEngineEvent(EngineEvent.VideoHandoverRate(1f))
        assertTrue(player.calls.isEmpty())
    }
}
