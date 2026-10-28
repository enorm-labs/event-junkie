package de.norm.events.account

import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component

/** `bff.account.notification{action}`: notifications the new-dates job wrote, and those read (#4078). Every series is registered at construction. */
@Component
class NotificationMetrics(
    registry: MeterRegistry
) {
    private val counters =
        NotificationAction.entries.associateWith { registry.counter(NAME, AccountMetrics.TAG_ACTION, it.tag) }

    fun record(
        action: NotificationAction,
        count: Long = 1
    ) = counters.getValue(action).increment(count.toDouble())

    companion object {
        const val NAME = "bff.account.notification"
    }
}

enum class NotificationAction(
    val tag: String
) {
    /** One per row the new-dates job wrote. */
    CREATED("created"),
    READ("read"),

    /** One per press of Mark all as read, whatever it marked. */
    READ_ALL("read_all")
}
