package dev.better.airplay.domain.model

/** Identifies the sender's remote-control endpoint for this session (AirPlay DACP-ID + Active-Remote token). */
data class RemoteTarget(val dacpId: String, val activeRemote: String)

/** Playback commands the TV can send back to the sender, like an Apple TV remote does. */
enum class RemoteCommand { PLAY_PAUSE, NEXT, PREVIOUS, VOLUME_UP, VOLUME_DOWN }
