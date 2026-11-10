package dev.better.airplay.data

import dev.better.airplay.data.discovery.NsdServiceAdvertiser
import dev.better.airplay.data.engine.DmapParser
import dev.better.airplay.data.engine.NativeEventParser
import dev.better.airplay.domain.model.ReceiverName
import dev.better.airplay.domain.model.VideoFormat
import dev.better.airplay.domain.port.EngineEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class DmapParserTest {
    private fun item(tag: String, value: ByteArray) = ByteArrayOutputStream().apply {
        write(tag.toByteArray(Charsets.US_ASCII))
        write(byteArrayOf((value.size ushr 24).toByte(), (value.size ushr 16).toByte(), (value.size ushr 8).toByte(), value.size.toByte()))
        write(value)
    }.toByteArray()

    private fun str(tag: String, v: String) = item(tag, v.toByteArray(Charsets.UTF_8))

    @Test fun `parses title artist album inside an mlit container`() {
        val body = str("minm", "Blinding Lights") + str("asar", "The Weeknd") + str("asal", "After Hours") +
            item("astm", byteArrayOf(0, 3, 0x2c, 0x10))   // unrelated numeric field is skipped
        val np = DmapParser.parse(item("mlit", body))!!
        assertEquals("Blinding Lights", np.title); assertEquals("The Weeknd", np.artist); assertEquals("After Hours", np.album)
    }

    @Test fun `handles unicode and missing fields`() {
        val np = DmapParser.parse(str("minm", "Café — Ñandú"))!!
        assertEquals("Café — Ñandú", np.title); assertNull(np.artist)
    }

    @Test fun `truncated or empty data is ignored safely`() {
        assertNull(DmapParser.parse(ByteArray(0)))
        assertNull(DmapParser.parse(byteArrayOf('m'.code.toByte(), 'i'.code.toByte(), 'n'.code.toByte(), 'm'.code.toByte(), 0, 0, 0, 99)))
    }
}

class NativeEventParserTest {
    @Test fun `client request parses the curly-apostrophe name iOS sends`() {
        val e = NativeEventParser.parse("client_request", "Raazi’s iPhone | iPhone13,4 | B2:E9:50:81:36:AE")
        e as EngineEvent.SenderConnecting
        assertEquals("Raazi’s iPhone", e.sender.name); assertEquals("iPhone13,4", e.sender.model)
    }

    @Test fun `video codec event from a real iPhone session`() {
        val e = NativeEventParser.parse("video_codec", "codec=2 source=998x2160 offset=1421,0 size=998x2160")
        e as EngineEvent.MirroringStarted
        assertEquals(VideoFormat.Codec.HEVC, e.format?.codec)
    }

    @Test fun `ALAC codec means a music session, AAC-ELD does not`() {
        assertEquals(EngineEvent.AudioStreamStarted, NativeEventParser.parse("audio_codec", "2"))
        assertNull(NativeEventParser.parse("audio_codec", "8"))
    }

    @Test fun `video size and unknown events`() {
        assertEquals(EngineEvent.VideoSizeChanged(3840, 2160), NativeEventParser.parse("video_size", "3840x2160"))
        assertNull(NativeEventParser.parse("conn_feedback", ""))
    }
}

class TxtRecordTest {
    @Test fun `parses length-prefixed key=value entries`() {
        val txt = byteArrayOf(4) + "a=bc".toByteArray() + byteArrayOf(2) + "pw".toByteArray()
        assertEquals(listOf("a" to "bc", "pw" to ""), NsdServiceAdvertiser.parseTxt(txt))
    }

    @Test fun `stops at a length that runs past the end`() {
        assertEquals(emptyList<Pair<String, String>>(), NsdServiceAdvertiser.parseTxt(byteArrayOf(9, 'a'.code.toByte())))
    }
}

class ReceiverNameTest {
    @Test fun `strips characters AirPlay or mDNS reject and limits bytes`() {
        assertEquals("Living Room", ReceiverName.sanitize("  Living\t Room.local "))
        assertEquals("TVhome", ReceiverName.sanitize("TV@home"))
        assertTrue(ReceiverName.sanitize("é".repeat(60)).toByteArray(Charsets.UTF_8).size <= 48)
    }
}
