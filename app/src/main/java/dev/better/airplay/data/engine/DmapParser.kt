package dev.better.airplay.data.engine

import dev.better.airplay.domain.model.NowPlaying

/**
 * Parses DMAP-tagged track metadata (as sent in AirPlay SET_PARAMETER): a sequence of
 * 4-char tag + 4-byte big-endian length + value, with container tags (mlit, mlcl) nesting.
 */
internal object DmapParser {
    private val containers = setOf("mlit", "mlcl", "cmst", "mdcl")

    fun parse(data: ByteArray): NowPlaying? {
        val fields = HashMap<String, String>()
        walk(data, 0, data.size, fields, depth = 0)
        val title = fields["minm"]
        val artist = fields["asar"] ?: fields["asaa"]
        val album = fields["asal"]
        return if (title == null && artist == null && album == null) null else NowPlaying(title, artist, album)
    }

    private fun walk(d: ByteArray, start: Int, end: Int, out: MutableMap<String, String>, depth: Int) {
        var i = start
        while (i + 8 <= end) {
            val tag = String(d, i, 4, Charsets.US_ASCII)
            val len = ((d[i + 4].toInt() and 0xFF) shl 24) or ((d[i + 5].toInt() and 0xFF) shl 16) or
                ((d[i + 6].toInt() and 0xFF) shl 8) or (d[i + 7].toInt() and 0xFF)
            val valueStart = i + 8
            if (len < 0 || valueStart + len > end) return
            if (tag in containers && depth < 4) {
                walk(d, valueStart, valueStart + len, out, depth + 1)
            } else if (tag == "minm" || tag == "asar" || tag == "asaa" || tag == "asal") {
                out.putIfAbsent(tag, String(d, valueStart, len, Charsets.UTF_8).trim())
            }
            i = valueStart + len
        }
    }
}
