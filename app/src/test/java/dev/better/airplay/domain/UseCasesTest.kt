package dev.better.airplay.domain

import dev.better.airplay.domain.model.AccessMode
import dev.better.airplay.domain.model.CodecPreference
import dev.better.airplay.domain.model.DisplayCapabilities
import dev.better.airplay.domain.model.LatencyMode
import dev.better.airplay.domain.model.NowPlaying
import dev.better.airplay.domain.model.ReceiverSettings
import dev.better.airplay.domain.model.ReceiverStatus
import dev.better.airplay.domain.model.RefreshRate
import dev.better.airplay.domain.model.Resolution
import dev.better.airplay.domain.model.Sender
import dev.better.airplay.domain.model.VideoFormat
import dev.better.airplay.domain.port.EngineEvent
import dev.better.airplay.domain.port.StartResult
import dev.better.airplay.domain.usecase.BuildReceiverConfig
import dev.better.airplay.domain.usecase.RenameReceiver
import dev.better.airplay.domain.usecase.ResolveReceiverName
import dev.better.airplay.domain.usecase.SessionMediaPolicy
import dev.better.airplay.domain.usecase.StartReceiver
import dev.better.airplay.domain.usecase.TrackSession
import dev.better.airplay.domain.usecase.UpdateSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResolveReceiverNameTest {
    @Test fun `follows the TV name by default`() {
        assertEquals("Family room TV", ResolveReceiverName(FakeSettings(), FakeDevice())())
    }

    @Test fun `custom name wins`() {
        val s = FakeSettings(ReceiverSettings(customName = "Den"))
        assertEquals("Den", ResolveReceiverName(s, FakeDevice())())
    }

    @Test fun `falls back when the TV name is unusable`() {
        assertEquals("Android TV", ResolveReceiverName(FakeSettings(), FakeDevice(name = "@@"))())
    }
}

class BuildReceiverConfigTest {
    private fun build(s: ReceiverSettings, caps: DisplayCapabilities = DisplayCapabilities(3840, 2160, true, 60)) =
        FakeSettings(s).let { repo ->
            val dev = FakeDevice(caps = caps)
            BuildReceiverConfig(repo, dev, ResolveReceiverName(repo, dev))()
        }

    @Test fun `auto on a 4K HEVC TV advertises 4K HEVC at the display rate`() {
        val c = build(ReceiverSettings())
        assertEquals(3840, c.width); assertEquals(2160, c.height)
        assertTrue(c.hevc); assertEquals(60, c.refreshHz); assertEquals(60, c.maxFps)
    }

    @Test fun `H264 only never advertises more than 1080p`() {
        val c = build(ReceiverSettings(codec = CodecPreference.H264_ONLY, resolution = Resolution.UHD_2160))
        assertFalse(c.hevc); assertEquals(1920, c.width); assertEquals(1080, c.height)
    }

    @Test fun `TV without HEVC 4K falls back to 1080p H264`() {
        val c = build(ReceiverSettings(), DisplayCapabilities(1920, 1080, hevc4k60 = false, refreshHz = 60))
        assertFalse(c.hevc); assertEquals(1920, c.width)
    }

    @Test fun `fps cap never exceeds refresh rate`() {
        val c = build(ReceiverSettings(refreshRate = RefreshRate.HZ_30))
        assertEquals(30, c.refreshHz); assertEquals(30, c.maxFps)
    }

    @Test fun `audio delay is added to the 250 ms base and never negative`() {
        assertEquals(290_000, build(ReceiverSettings(audioDelayMs = 40)).audioDelayMicros)
        assertEquals(0, build(ReceiverSettings(audioDelayMs = -500)).audioDelayMicros)
    }

    @Test fun `PIN mode maps to requirePin`() {
        assertTrue(build(ReceiverSettings(access = AccessMode.ONSCREEN_PIN)).requirePin)
    }

    @Test fun `video handover stays off until the HLS player exists`() {
        assertFalse(build(ReceiverSettings()).videoHandover)
    }
}

class StartReceiverTest {
    @Test fun `publishes Ready with the port on success`() {
        val store = StatusStore(); val repo = FakeSettings(); val dev = FakeDevice()
        StartReceiver(FakeEngine(StartResult.Started(7000)), BuildReceiverConfig(repo, dev, ResolveReceiverName(repo, dev)), store)()
        assertEquals(ReceiverStatus.Ready(7000), store.snapshot.status)
        assertEquals("Family room TV", store.snapshot.name)
    }

    @Test fun `publishes Failed on error`() {
        val store = StatusStore(); val repo = FakeSettings(); val dev = FakeDevice()
        StartReceiver(FakeEngine(StartResult.Failed(-40)), BuildReceiverConfig(repo, dev, ResolveReceiverName(repo, dev)), store)()
        assertEquals(ReceiverStatus.Failed(-40), store.snapshot.status)
    }
}

class TrackSessionTest {
    private val store = StatusStore().apply { update { it.copy(status = ReceiverStatus.Ready(7000)) } }
    private val track = TrackSession(store)
    private val phone = Sender("Raazi's iPhone", "iPhone13,4")

    @Test fun `mirroring session keeps the sender and format`() {
        track.onEngineEvent(EngineEvent.SenderConnecting(phone))
        track.onEngineEvent(EngineEvent.MirroringStarted(VideoFormat(VideoFormat.Codec.HEVC, 3840, 2160)))
        val st = store.snapshot.status as ReceiverStatus.Mirroring
        assertEquals(phone, st.sender); assertEquals("4K · HEVC", st.format?.label)
    }

    @Test fun `mirroring audio does not turn the session into an audio session`() {
        track.onEngineEvent(EngineEvent.SenderConnecting(phone))
        track.onEngineEvent(EngineEvent.MirroringStarted(null))
        track.onEngineEvent(EngineEvent.AudioStreamStarted)
        assertTrue(store.snapshot.status is ReceiverStatus.Mirroring)
    }

    @Test fun `music session collects now playing and clears it on end, back to the same port`() {
        track.onEngineEvent(EngineEvent.SenderConnecting(phone))
        track.onEngineEvent(EngineEvent.AudioStreamStarted)
        track.onEngineEvent(EngineEvent.NowPlayingChanged(NowPlaying("Song", "Artist", "Album")))
        assertTrue(store.snapshot.status is ReceiverStatus.PlayingAudio)
        assertEquals("Song", store.snapshot.nowPlaying?.title)
        track.onEngineEvent(EngineEvent.SessionEnded)
        assertEquals(ReceiverStatus.Ready(7000), store.snapshot.status)
        assertNull(store.snapshot.nowPlaying)
    }
}

class RenameReceiverTest {
    @Test fun `renaming restarts once and clearing returns to the TV name`() {
        val repo = FakeSettings(); val dev = FakeDevice(); val ctl = FakeController()
        val rename = RenameReceiver(repo, dev, ResolveReceiverName(repo, dev), ctl)
        assertTrue(rename("Den TV")); assertEquals("Den TV", repo.value.customName); assertEquals(1, ctl.restarts)
        assertFalse(rename("Den TV")); assertEquals(1, ctl.restarts)
        assertTrue(rename(null)); assertNull(repo.value.customName)
    }

    @Test fun `typing the TV's own name is the same as following it`() {
        val repo = FakeSettings(); val dev = FakeDevice()
        RenameReceiver(repo, dev, ResolveReceiverName(repo, dev), FakeController())("Family room TV")
        assertNull(repo.value.customName)
    }
}

class UpdateSettingsTest {
    @Test fun `advertised settings restart, app-side settings do not`() {
        val repo = FakeSettings(); val ctl = FakeController(); val update = UpdateSettings(repo, ctl)
        assertEquals(UpdateSettings.Effect.RESTARTED, update { it.copy(resolution = Resolution.FHD_1080) })
        assertEquals(UpdateSettings.Effect.APPLIED_NOW, update { it.copy(latencyMode = LatencyMode.LOW) })
        assertEquals(UpdateSettings.Effect.NONE, update { it })
        assertEquals(1, ctl.restarts)
    }
}

class SessionMediaPolicyTest {
    @Test fun `mirror audio switch mutes mirroring only, never music`() {
        val engine = FakeEngine(); val policy = SessionMediaPolicy(engine, FakeSettings(ReceiverSettings(mirrorAudio = false)))
        val snap = StatusStore().snapshot
        policy.onStatus(snap.copy(status = ReceiverStatus.Mirroring(null, null)))
        assertEquals(true, engine.muted)
        policy.onStatus(snap.copy(status = ReceiverStatus.PlayingAudio(null)))
        assertEquals(false, engine.muted)
    }

    @Test fun `smooth latency disables the low-latency decoder path`() {
        val engine = FakeEngine()
        SessionMediaPolicy(engine, FakeSettings(ReceiverSettings(latencyMode = LatencyMode.SMOOTH))).onStatus(StatusStore().snapshot)
        assertEquals(false, engine.lowLatency)
    }
}
