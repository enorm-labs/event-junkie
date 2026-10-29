package de.norm.events.mail

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Duration

private val logger = KotlinLogging.logger {}

/**
 * Reads the Scaleway project's mail count of this calendar month into [MailQuotaMetrics], so `ej-mail-quota-high`
 * fires before the quota stops sending (#4017). `scripts/tem-quota.sh` reads the same two calls for `/daily-check`.
 *
 * Exists only when the read-only key is set: both clusters send from one project, so production alone reads it.
 * Every replica runs it, so a rule takes `max` over the pods.
 */
@Component
@ConditionalOnProperty("app.mail.quota.api-key")
@Suppress("LongParameterList") // Four collaborators and the three settings of one client.
class MailQuotaRefresh(
    webClients: WebClient.Builder,
    private val json: JsonMapper,
    private val clock: Clock,
    private val metrics: MailQuotaMetrics,
    @Value("\${app.mail.quota.api-key}") private val apiKey: String,
    @Value("\${app.mail.quota.project-id}") private val projectId: String,
    @Value("\${app.mail.quota.base-url}") baseUrl: String
) {
    private val client = webClients.baseUrl(baseUrl).build()

    @Scheduled(initialDelayString = "\${app.mail.quota.initial-delay}", fixedDelayString = "\${app.mail.quota.refresh}")
    @Suppress("TooGenericExceptionCaught")
    suspend fun refresh() {
        try {
            val count = get("project-consumption").path("monthly_emails_count").asLong()
            val free = get("offer-subscriptions").path("offer_subscriptions").maxOfOrNull { it.path("included_monthly_emails").asLong() } ?: 0
            metrics.publish(count, free, clock.instant())
            logger.info { "Mail quota: $count mails this month, $free included" }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "Mail quota: reading the Scaleway count failed, the gauges keep their last values" }
        }
    }

    private suspend fun get(path: String) =
        json.readTree(
            client
                .get()
                .uri { it.pathSegment(path).queryParam("project_id", projectId).build() }
                .header("X-Auth-Token", apiKey)
                .retrieve()
                .bodyToMono(String::class.java)
                .timeout(TIMEOUT)
                .awaitSingle()
        )

    private companion object {
        val TIMEOUT: Duration = Duration.ofSeconds(10)
    }
}
