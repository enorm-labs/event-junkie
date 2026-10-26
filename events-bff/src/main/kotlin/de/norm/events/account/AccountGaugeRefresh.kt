package de.norm.events.account

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration

private val logger = KotlinLogging.logger {}

/** Sets the account gauges in [AccountMetrics] from the database. Every replica runs it, so `max` over the pods is the count. */
@Component
class AccountGaugeRefresh(
    private val accounts: AccountStore,
    private val follows: FollowStore,
    private val metrics: AccountMetrics,
    private val clock: Clock
) {
    @Scheduled(fixedDelayString = "\${app.accounts.gauge-refresh:PT5M}")
    @Suppress("TooGenericExceptionCaught")
    suspend fun refresh() {
        try {
            val now = clock.instant()
            val counts = accounts.counts(now.minus(ACTIVE_WINDOW), now)
            val byKind = follows.countsByKind()
            metrics.publish(counts, byKind)
            logger.debug { "Account gauges: $counts, follows $byKind" }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "Account gauges: refresh failed, the gauges keep their last values" }
        }
    }

    private companion object {
        val ACTIVE_WINDOW: Duration = Duration.ofDays(30)
    }
}
