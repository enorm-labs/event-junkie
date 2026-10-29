package de.norm.events.mail

import io.micrometer.core.instrument.MeterRegistry
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

/**
 * The month's mail count against the quota (#4017). Registered on every cluster, so the alert rules' streams exist
 * where [MailQuotaRefresh] does not run and the gauges read 0 there (`deploy/alerts/README.md`).
 */
@Component
class MailQuotaMetrics(
    registry: MeterRegistry,
    @Value("\${app.mail.quota.limit}") limit: Long
) {
    private val sent = registry.gauge(SENT, AtomicLong(0))
    private val included = registry.gauge(INCLUDED, AtomicLong(0))
    private val lastSuccess = registry.gauge(LAST_SUCCESS, AtomicLong(0))

    /** Held although it never changes: the registry keeps a gauge's object weakly, and a collected one reads 0. */
    val limit: AtomicLong = registry.gauge(LIMIT, AtomicLong(limit))

    fun publish(
        count: Long,
        free: Long,
        at: Instant
    ) {
        sent.set(count)
        included.set(free)
        lastSuccess.set(at.epochSecond)
    }

    companion object {
        const val SENT = "bff.mail.quota.sent"

        /** The quota that stops sending. The API does not return it, so it is a setting. */
        const val LIMIT = "bff.mail.quota.limit"

        /** The plan's free mails: past them each mail is billed, and still sent. */
        const val INCLUDED = "bff.mail.quota.included"

        /** Epoch seconds of the last read that succeeded, for `ej-mail-quota-stale`. */
        const val LAST_SUCCESS = "bff.mail.quota.last_success"
    }
}
