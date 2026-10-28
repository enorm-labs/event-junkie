package de.norm.events.account

import de.norm.events.common.BERLIN
import de.norm.events.push.PushResult
import de.norm.events.push.PushSender
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

private val logger = KotlinLogging.logger {}

/**
 * Announces new dates of what accounts follow: a notification under the header's bell for every follow (#4078), and a
 * push to the browsers of accounts that follow with the bell on (#3894, ADR-055). New is `events.event.created_at`,
 * which an update keeps, past a cursor that each run moves. The cursor moves also where no push is configured.
 *
 * Every replica runs it, and [PushSubscriptionStore.claimWindow] hands each window to one of them, so a date goes out
 * once. A window is claimed before it is sent, so a run that fails halfway loses the rest of its window rather than
 * sending it twice. A re-keyed event is a new row with a new `created_at`, so its followers hear of it again.
 * [PushSender] counts every send in `bff.push.sent{outcome}`.
 *
 * Each claimed window logs one line, also an empty one, so a quiet window is not mistaken for a job that never ran (#4068).
 * The first run comes a minute after boot, not a full delay: staging rolls out on every merge, and a full delay restarted
 * at each rollout held the pushes back. Running early is safe, because the cursor and the lag decide what is new.
 */
@Component
class NewDatesJob(
    private val store: PushSubscriptionStore,
    private val notifications: NotificationStore,
    private val metrics: NotificationMetrics,
    private val sender: ObjectProvider<PushSender>,
    private val clock: Clock,
    @Value("\${app.push.new-dates-lag}") private val lag: Duration
) {
    @Scheduled(fixedDelayString = "\${app.push.new-dates-delay}", initialDelayString = "\${app.push.new-dates-initial-delay}")
    @Suppress("TooGenericExceptionCaught") // Any failure ends this run with a WARN; the next run claims the next window.
    suspend fun run() {
        try {
            val upTo = clock.instant().minus(lag)
            val from = store.claimWindow(upTo)
            if (from == null) {
                logger.debug { "New-dates push: the window up to $upTo is claimed already" }
                return
            }
            val today = LocalDate.now(clock.withZone(BERLIN))
            notify(from, upTo, today)
            val push = sender.ifAvailable ?: return
            val dates = store.newDates(from, upTo, today)
            if (dates.isEmpty()) {
                logger.info { "New-dates push: no new dates from $from up to $upTo" }
                return
            }
            val byAccount = dates.groupBy { it.accountId }
            logger.info { "New-dates push started: ${dates.size} dates for ${byAccount.size} accounts" }
            val outcomes =
                byAccount.values
                    .flatMap { send(push, it) }
                    .groupingBy { it }
                    .eachCount()
            logger.info { "New-dates push finished: ${outcomes.entries.joinToString { "${it.key.tag} ${it.value}" }}" }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "New-dates push failed" }
        }
    }

    /** Writes the window's notifications. A failure is logged, and the pushes still go out. */
    @Suppress("TooGenericExceptionCaught") // The pushes of the window must not depend on the notifications.
    private suspend fun notify(
        from: Instant,
        upTo: Instant,
        today: LocalDate
    ) {
        try {
            val recorded = notifications.record(from, upTo, today, clock.instant())
            metrics.record(NotificationAction.CREATED, recorded.rows.toLong())
            logger.info { "New-dates notifications: ${recorded.rows} for ${recorded.accounts} accounts, from $from up to $upTo" }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "New-dates notifications failed, from $from up to $upTo" }
        }
    }

    /** Sends one account's dates to each of its browsers, and forgets a browser whose subscription is gone. */
    private suspend fun send(
        push: PushSender,
        dates: List<NewDate>
    ): List<PushResult> {
        val message = NewDatesPush.message(dates)
        return store.targets(dates.first().accountId).map { target ->
            push.send(target, message).also { result ->
                when (result) {
                    PushResult.SENT -> store.sent(target.endpoint, clock.instant())
                    PushResult.GONE -> store.delete(target.endpoint)
                    PushResult.REJECTED, PushResult.FAILED -> Unit
                }
            }
        }
    }
}
