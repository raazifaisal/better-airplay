package dev.better.airplay.data.remote

import dev.better.airplay.domain.model.RemoteCommand

/**
 * DACP (Digital Audio Control Protocol) as used by AirPlay senders: the sender advertises
 * `_dacp._tcp` as "iTunes_Ctrl_<DACP-ID>"; the receiver sends `GET /ctrl-int/1/<command>` with an
 * `Active-Remote: <token>` header. Pure helpers, unit-tested.
 */
object Dacp {
    const val SERVICE_TYPE = "_dacp._tcp"
    private const val PREFIX = "iTunes_Ctrl_"

    fun path(command: RemoteCommand): String = "/ctrl-int/1/" + when (command) {
        RemoteCommand.PLAY_PAUSE -> "playpause"
        RemoteCommand.NEXT -> "nextitem"
        RemoteCommand.PREVIOUS -> "previtem"
        RemoteCommand.VOLUME_UP -> "volumeup"
        RemoteCommand.VOLUME_DOWN -> "volumedown"
    }

    fun serviceName(dacpId: String) = PREFIX + dacpId.uppercase()

    fun matches(serviceName: String, dacpId: String) = serviceName.equals(serviceName(dacpId), ignoreCase = true)
}
