package de.norm.events.account

import de.norm.events.common.BERLIN
import de.norm.events.push.PushResult
import de.norm.events.push.PushSender
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import org.springframework.beans.factory.ObjectProvider
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.LocalTime
import java.time.ZonedDateTime

private val logger = KotlinLogging.logger {}

/**
 * Reminds accounts on the day of the events they marked as going or interested (#4253): one push per account at 10:00
 * Berlin time, to the browsers of those with "Reminders on the day" on. No notification under the bell: My calendar
 * lists the day's events already.
 *
 * Every replica runs it every few minutes. From 10:00, [ReminderStore.claimDay] hands the day to one of them, so the
 * reminder goes out once, also when the BFF starts after 10:00. A day is claimed before it is sent, so a run that fails
 * halfway loses the rest of that day rather than sending it twice. [PushSender] counts every send in `bff.push.sent{outcome}`.
 */
@Component
class RemindersJob(
    private val store: ReminderStore,
    private val subscriptions: PushSubscriptionStore,
    private val sender: ObjectProvider<PushSender>,
    private val clock: Clock
) {
    @Scheduled(fixedDelayString = "\${app.push.reminders-delay}", initialDelayString = "\${app.push.reminders-initial-delay}")
    @Suppress("TooGenericExceptionCaught") // Any failure ends this run with a WARN; the next day is claimed as usual.
    suspend fun run() {
        try {
            val now = ZonedDateTime.now(clock.withZone(BERLIN))
            if (now.toLocalTime() < SEND_AT) return
            val today = now.toLocalDate()
            if (!store.claimDay(today)) {
                logger.debug { "Reminders: $today is claimed already" }
                return
            }
            val push = sender.ifAvailable
            val due = if (push == null) emptyList() else store.due(today)
            val byAccount = due.groupBy { it.accountId }
            logger.info { "Reminders: ${byAccount.size} accounts, ${due.size} events for $today" }
            if (push == null || due.isEmpty()) return
            val outcomes =
                byAccount.values
                    .flatMap { send(push, it) }
                    .groupingBy { it }
                    .eachCount()
            logger.info { "Reminders push finished: ${outcomes.entries.joinToString { "${it.key.tag} ${it.value}" }}" }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "Reminders failed" }
        }
    }

    /** Sends one account's reminder to each of its browsers, and forgets a browser whose subscription is gone. */
    private suspend fun send(
        push: PushSender,
        events: List<ReminderDue>
    ): List<PushResult> {
        val message = ReminderPush.message(events)
        return subscriptions.targets(events.first().accountId).map { target ->
            push.send(target, message).also { result ->
                when (result) {
                    PushResult.SENT -> subscriptions.sent(target.endpoint, clock.instant())
                    PushResult.GONE -> subscriptions.delete(target.endpoint)
                    PushResult.REJECTED, PushResult.FAILED -> Unit
                }
            }
        }
    }

    private companion object {
        /** Late enough not to wake anyone, early enough to plan the evening (decided on #4253). */
        val SEND_AT: LocalTime = LocalTime.of(10, 0)
    }
}
