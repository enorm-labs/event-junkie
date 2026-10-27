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
import java.time.LocalDate

private val logger = KotlinLogging.logger {}

/**
 * Pushes new dates to the browsers of accounts that follow an artist, venue or promoter with the bell on (#3894,
 * ADR-055). New is `events.event.created_at`, which an update keeps, past a cursor that each run moves.
 *
 * Every replica runs it, and [PushSubscriptionStore.claimWindow] hands each window to one of them, so a date goes out
 * once. A window is claimed before it is sent, so a run that fails halfway loses the rest of its window rather than
 * sending it twice. A re-keyed event is a new row with a new `created_at`, so its followers hear of it again.
 * [PushSender] counts every send in `bff.push.sent{outcome}`.
 */
@Component
class NewDatesJob(
    private val store: PushSubscriptionStore,
    private val sender: ObjectProvider<PushSender>,
    private val clock: Clock,
    @Value("\${app.push.new-dates-lag}") private val lag: Duration
) {
    @Scheduled(fixedDelayString = "\${app.push.new-dates-delay}", initialDelayString = "\${app.push.new-dates-delay}")
    @Suppress("TooGenericExceptionCaught") // Any failure ends this run with a WARN; the next run claims the next window.
    suspend fun run() {
        val push = sender.ifAvailable ?: return
        try {
            val upTo = clock.instant().minus(lag)
            val from = store.claimWindow(upTo) ?: return
            val dates = store.newDates(from, upTo, LocalDate.now(clock.withZone(BERLIN)))
            if (dates.isEmpty()) return
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
