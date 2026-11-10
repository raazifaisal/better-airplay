package dev.better.airplay.domain

import dev.better.airplay.domain.model.ReceiverSnapshot
import java.util.concurrent.CopyOnWriteArrayList

/** Single source of truth for receiver status. Thread-safe; listeners run on the updating thread. */
class StatusStore {
    private val listeners = CopyOnWriteArrayList<(ReceiverSnapshot) -> Unit>()

    @Volatile var snapshot = ReceiverSnapshot()
        private set

    @Synchronized
    fun update(transform: (ReceiverSnapshot) -> ReceiverSnapshot) {
        val next = transform(snapshot)
        if (next == snapshot) return
        snapshot = next
        listeners.forEach { it(next) }
    }

    fun observe(listener: (ReceiverSnapshot) -> Unit): AutoCloseable {
        listeners += listener
        listener(snapshot)
        return AutoCloseable { listeners -= listener }
    }
}
