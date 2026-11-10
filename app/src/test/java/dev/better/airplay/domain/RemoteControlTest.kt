package dev.better.airplay.domain

import dev.better.airplay.data.engine.NativeEventParser
import dev.better.airplay.data.remote.Dacp
import dev.better.airplay.domain.model.NowPlaying
import dev.better.airplay.domain.model.RemoteCommand
import dev.better.airplay.domain.model.RemoteTarget
import dev.better.airplay.domain.model.Sender
import dev.better.airplay.domain.port.EngineEvent
import dev.better.airplay.domain.port.RemoteControl
import dev.better.airplay.domain.usecase.SendRemoteCommand
import dev.better.airplay.domain.usecase.TrackSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeRemote : RemoteControl {
    val sent = mutableListOf<Pair<RemoteTarget, RemoteCommand>>()
    override fun send(target: RemoteTarget, command: RemoteCommand) { sent += target to command }
}

class DacpProtocolTest {
    @Test fun `command paths match the DACP control interface`() {
        assertEquals("/ctrl-int/1/playpause", Dacp.path(RemoteCommand.PLAY_PAUSE))
        assertEquals("/ctrl-int/1/nextitem", Dacp.path(RemoteCommand.NEXT))
        assertEquals("/ctrl-int/1/previtem", Dacp.path(RemoteCommand.PREVIOUS))
        assertEquals("/ctrl-int/1/volumeup", Dacp.path(RemoteCommand.VOLUME_UP))
        assertEquals("/ctrl-int/1/volumedown", Dacp.path(RemoteCommand.VOLUME_DOWN))
    }

    @Test fun `the sender advertises its control service as iTunes_Ctrl_ plus DACP-ID`() {
        assertEquals("iTunes_Ctrl_2C265112AA12A5DE", Dacp.serviceName("2c265112aa12a5de"))
        assertTrue(Dacp.matches("iTunes_Ctrl_2C265112AA12A5DE", "2C265112AA12A5DE"))
        assertFalse(Dacp.matches("iTunes_Ctrl_FFFF", "2C265112AA12A5DE"))
    }

    @Test fun `native dacp event carries id and active-remote (captured from a real iPhone)`() {
        val e = NativeEventParser.parse("dacp", "2C265112AA12A5DE 3304892449") as EngineEvent.RemoteControlAvailable
        assertEquals(RemoteTarget("2C265112AA12A5DE", "3304892449"), e.target)
        assertNull(NativeEventParser.parse("dacp", " "))
    }
}

class SendRemoteCommandTest {
    private val store = StatusStore()
    private val track = TrackSession(store)
    private val remote = FakeRemote()
    private val send = SendRemoteCommand(remote, store)
    private val target = RemoteTarget("2C265112AA12A5DE", "3304892449")

    private fun musicSession(withRemote: Boolean) {
        track.onEngineEvent(EngineEvent.SenderConnecting(Sender("iPhone")))
        track.onEngineEvent(EngineEvent.AudioStreamStarted)
        track.onEngineEvent(EngineEvent.NowPlayingChanged(NowPlaying("Song", "Artist", "Album")))
        if (withRemote) track.onEngineEvent(EngineEvent.RemoteControlAvailable(target))
    }

    @Test fun `sends to the session's sender`() {
        musicSession(withRemote = true)
        assertTrue(send(RemoteCommand.NEXT))
        assertEquals(listOf(target to RemoteCommand.NEXT), remote.sent)
    }

    @Test fun `play-pause flips the on-screen state immediately`() {
        musicSession(withRemote = true)
        send(RemoteCommand.PLAY_PAUSE)
        assertFalse(store.snapshot.playing)
        send(RemoteCommand.PLAY_PAUSE)
        assertTrue(store.snapshot.playing)
    }

    @Test fun `without a remote target nothing is sent`() {
        musicSession(withRemote = false)
        assertFalse(send(RemoteCommand.PLAY_PAUSE))
        assertTrue(remote.sent.isEmpty())
        assertTrue(store.snapshot.playing)
    }

    @Test fun `remote target is cleared when the session ends`() {
        musicSession(withRemote = true)
        track.onEngineEvent(EngineEvent.SessionEnded)
        assertNull(store.snapshot.remote)
    }
}
