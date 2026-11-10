package dev.better.airplay.domain.model

/** Rules for names advertised over mDNS / AirPlay. */
object ReceiverName {
    /** mDNS labels are max 63 bytes; RAOP prefixes "XXXXXXXXXXXX@", so stay well under. */
    private const val MAX_BYTES = 48

    fun sanitize(raw: String): String {
        var s = raw.replace(Regex("\\s+"), " ").trim()
            .replace(".local", "", ignoreCase = true)   // UxPlay rejects names containing ".local"
            .filter { it >= ' ' && it != '@' }           // '@' separates the RAOP prefix
        while (s.toByteArray(Charsets.UTF_8).size > MAX_BYTES) s = s.dropLast(1)
        return s.trim()
    }
}
