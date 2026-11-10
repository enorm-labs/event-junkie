package de.norm.events.account

import de.norm.events.common.BERLIN
import de.norm.events.common.rethrowIfCancelled
import de.norm.events.push.PushSender
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.LocalDate

private val logger = KotlinLogging.logger {}

/**
 * Announces changes to events that accounts marked as interested or going (#4045): a cancellation, a postponement, a
 * relocation, a new date, start or venue. A notification under the header's bell for every such account, and a push
 * to the browsers of those with "Changes to my plans" on. The changes are `events.event_change` rows past a cursor,
 * as [NewDatesJob] reads new events, and its notes on replicas, the lag and the first run hold here too.
 *
 * A change the venue makes by deleting the event leaves no row, and is not announced. A date move on a re-keyed source
 * is logged on the new event while the RSVP sits on the old one, so each run first carries the RSVPs across (#4139).
 */
@Component
class PlanChangesJob(
    private val store: PlanChangeStore,
    private val subscriptions: PushSubscriptionStore,
    private val metrics: NotificationMetrics,
    private val sender: ObjectProvider<PushSender>,
    private val clock: Clock,
    @Value("\${app.push.changes-lag}") private val lag: Duration
) {
    @Scheduled(fixedDelayString = "\${app.push.changes-delay}", initialDelayString = "\${app.push.changes-initial-delay}")
    @Suppress("TooGenericExceptionCaught", "SuspendFunSwallowedCancellation") // Any failure ends this run with a WARN; the next run claims the next window.
    suspend fun run() {
        try {
            val upTo = clock.instant().minus(lag)
            val from = store.claimWindow(upTo)
            if (from == null) {
                logger.debug { "Plan changes: the window up to $upTo is claimed already" }
                return
            }
            val (rsvps, notifications) = store.carryOver(upTo)
            if (rsvps + notifications > 0) {
                logger.info { "Plan changes: carried $rsvps RSVP(s) and $notifications notification(s) across re-keyed date moves" }
            }
            val today = LocalDate.now(clock.withZone(BERLIN))
            val fresh = store.record(from, upTo, today, today.atStartOfDay(BERLIN).toInstant(), clock.instant())
            metrics.record(NotificationAction.CREATED, fresh.size.toLong())
            val push = sender.ifAvailable
            val due = if (push == null) emptyList() else store.due(fresh)
            logger.info { "Plan changes: ${fresh.size} new today from $from up to $upTo, ${due.size} to push" }
            if (push == null || due.isEmpty()) return
            val outcomes = subscriptions.fanOut(push, due.groupBy { it.accountId }, clock) { PlanChangePush.message(it) }
            logger.info { "Plan changes push finished: ${outcomes.summary()}" }
        } catch (e: Exception) {
            rethrowIfCancelled()
            logger.warn(e) { "Plan changes failed" }
        }
    }
}
