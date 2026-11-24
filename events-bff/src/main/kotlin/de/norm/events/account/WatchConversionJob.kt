package de.norm.events.account

import de.norm.events.common.BERLIN
import de.norm.events.common.rethrowIfCancelled
import de.norm.events.push.PushSender
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.beans.factory.ObjectProvider
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.LocalDate

private val logger = KotlinLogging.logger {}

/**
 * Turns watches into artist follows (#5214): a watch whose MusicBrainz id an EXACT artist row now carries becomes a follow
 * of that row, and the bell announces the artist's upcoming events. [NewDatesJob] cannot: it announces by `created_at`
 * past its cursor, and the verdict comes on a tick after the import that stored the event.
 *
 * Every replica runs it. [WatchStore.convert] locks each watch it takes, so a watch converts once. Each run logs one line,
 * also an empty one, so a quiet run is not mistaken for a job that never ran. A run that held stamps
 * `bff.account.watch.conversion.last_success`, and the job seeds it with its start time, so a release that breaks the
 * job ages from the deploy and fires `ej-watch-conversion-stale` (#5456).
 */
@Component
class WatchConversionJob(
    private val watches: WatchStore,
    private val subscriptions: PushSubscriptionStore,
    private val metrics: AccountMetrics,
    private val notificationMetrics: NotificationMetrics,
    private val sender: ObjectProvider<PushSender>,
    private val clock: Clock
) {
    init {
        metrics.stampWatchConversion(clock.instant())
    }

    @Scheduled(fixedDelayString = "\${app.watch.conversion-delay}", initialDelayString = "\${app.watch.conversion-initial-delay}")
    // Any failure ends this run with a WARN. The statement changed nothing, and the next run tries again.
    @Suppress("TooGenericExceptionCaught", "SuspendFunSwallowedCancellation")
    suspend fun run() {
        try {
            val rows = watches.convert(LocalDate.now(clock.withZone(BERLIN)), clock.instant())
            val converted = rows.map { it.watchId }.toSet().size
            val followed = rows.map { it.accountId to it.artistId }.toSet().size
            val dates = rows.mapNotNull { it.announced }
            metrics.record(WatchAction.CONVERTED, converted.toLong())
            notificationMetrics.record(NotificationAction.CREATED, dates.size.toLong())
            metrics.stampWatchConversion(clock.instant())
            logger.info { "Watch conversion: $converted watches to $followed follows, ${dates.size} notifications" }
            val due = rows.filter { it.push }.mapNotNull { it.announced }
            val push = sender.ifAvailable ?: return
            if (due.isEmpty()) return
            val outcomes = subscriptions.fanOut(push, due.groupBy { it.accountId }, clock) { NewDatesPush.message(it) }
            logger.info { "Watch conversion push finished: ${outcomes.summary()}" }
        } catch (e: Exception) {
            rethrowIfCancelled()
            metrics.record(WatchAction.CONVERSION_FAILED)
            logger.warn(e) { "Watch conversion failed" }
        }
    }
}
