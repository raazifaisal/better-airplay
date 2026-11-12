package dev.better.airplay.data

import dev.better.airplay.data.engine.NativeEventParser
import dev.better.airplay.domain.port.EngineEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * UxPlay needs the system language list to pick an audio track from YouTube's master playlist;
 * without it, it dereferences NULL and crashes the app (seen on the TV, 2026-11-12).
 */
class HlsLanguagesTest {
    @Test fun `full tags then their base languages, colon-separated, no duplicates`() {
        val list = listOf(java.util.Locale.forLanguageTag("en-IN"), java.util.Locale.forLanguageTag("hi-IN"),
            java.util.Locale.forLanguageTag("en-US"))
        assertEquals("en-IN:hi-IN:en-US:en:hi", dev.better.airplay.data.engine.HlsLanguages.of(list))
    }

    @Test fun `never empty, so the native side never sees a missing list`() {
        assertEquals("en", dev.better.airplay.data.engine.HlsLanguages.of(emptyList()))
        assertEquals("en", dev.better.airplay.data.engine.HlsLanguages.of(listOf(java.util.Locale.ROOT)))
    }
}

/** Native video-handover events (UxPlay on_video_* callbacks) to domain events. */
class VideoHandoverParserTest {
    private fun parse(event: String, arg: String) = NativeEventParser.parse(event, arg)

    @Test fun `video_play from YouTube gives the local HLS URL and start position`() {
        assertEquals(
            EngineEvent.VideoHandoverStarted("http://localhost:7000/master.m3u8", 12.5),
            parse("video_play", "http://localhost:7000/master.m3u8\n12.500000"),
        )
    }

    @Test fun `video_play keeps URLs with spaces, at-signs and query strings intact`() {
        val url = "https://cdn.example.com/a b/@clip.mp4?sig=x@y&t=1 2"
        assertEquals(EngineEvent.VideoHandoverStarted(url, 0.0), parse("video_play", "$url\n0.000000"))
    }

    @Test fun `video_play without a usable start position starts at 0`() {
        assertEquals(EngineEvent.VideoHandoverStarted("http://h/v.mp4", 0.0), parse("video_play", "http://h/v.mp4"))
        assertEquals(EngineEvent.VideoHandoverStarted("http://h/v.mp4", 0.0), parse("video_play", "http://h/v.mp4\nnan?"))
        assertEquals(EngineEvent.VideoHandoverStarted("http://h/v.mp4", 0.0), parse("video_play", "http://h/v.mp4\n-3"))
    }

    @Test fun `video_play with no location is ignored`() {
        assertNull(parse("video_play", ""))
        assertNull(parse("video_play", "\n5.0"))
        assertNull(parse("video_play", "   \n5.0"))
    }

    @Test fun `scrub maps to a seek, garbage is ignored`() {
        assertEquals(EngineEvent.VideoHandoverSeek(42.25), parse("video_scrub", "42.250000"))
        assertNull(parse("video_scrub", "abc"))
        assertEquals(EngineEvent.VideoHandoverSeek(0.0), parse("video_scrub", "-1.0"))
    }

    @Test fun `rate 0 pauses and 1 plays`() {
        assertEquals(EngineEvent.VideoHandoverRate(0f), parse("video_rate", "0.000000"))
        assertEquals(EngineEvent.VideoHandoverRate(1f), parse("video_rate", "1.000000"))
        assertNull(parse("video_rate", ""))
    }

    @Test fun `stop and playlist removal`() {
        assertEquals(EngineEvent.VideoHandoverStopped, parse("video_stop", ""))
        // playlistRemove (YouTube switching videos / ads): UxPlay pauses its player and keeps the position.
        assertEquals(EngineEvent.VideoHandoverRate(0f), parse("video_playlist_remove", "31.5"))
    }

    @Test fun `HLS shutdown and end-of-stream resets end the handover, mirroring resets do not`() {
        // reset_type_t: 0 NOHOLD, 1 RTP_SHUTDOWN, 2 HLS_SHUTDOWN, 3 HLS_EOS, 4 ON_VIDEO_PLAY, 5 RTP_TO_HLS_TEARDOWN
        assertEquals(EngineEvent.VideoHandoverStopped, parse("video_reset", "2"))
        assertEquals(EngineEvent.VideoHandoverStopped, parse("video_reset", "3"))
        assertNull(parse("video_reset", "1"))
        assertNull(parse("video_reset", "5"))
        assertNull(parse("video_reset", "x"))
    }
}
