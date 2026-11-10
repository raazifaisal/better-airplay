package dev.better.airplay.platform

import android.content.Context
import android.content.Intent
import dev.better.airplay.domain.port.ReceiverController

/** [ReceiverController] that forwards commands to the long-running [ReceiverService]. */
class ServiceReceiverController(private val context: Context) : ReceiverController {
    override fun ensureRunning() = send(null)
    override fun restart() = send(ReceiverService.ACTION_RESTART)
    override fun disconnect() = send(ReceiverService.ACTION_DISCONNECT)

    private fun send(action: String?) {
        context.startForegroundService(Intent(context, ReceiverService::class.java).setAction(action))
    }
}
