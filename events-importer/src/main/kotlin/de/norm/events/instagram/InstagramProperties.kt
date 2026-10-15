package de.norm.events.instagram

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * Configuration for the Instagram drafts, bound to `app.instagram`.
 *
 * **Off by default**, so no account is read and no caption leaves the platform until an operator
 * sets [enabled], a token and a key (ADR-048).
 */
@ConfigurationProperties(prefix = "app.instagram")
data class InstagramProperties(
    val enabled: Boolean = false,
    /** The Page token `scripts/instagram-page-token.sh` makes. Meta's data access for it lasts 90 days; the script renews it. */
    val accessToken: String = "",
    /** The Instagram business account the calls are made as: `eventjunkie.berlin`. */
    val igUserId: String = "17841471622815307",
    val graphBaseUrl: String = "https://graph.facebook.com",
    /** Meta retires a version about two years after its release. */
    val graphVersion: String = "v26.0",
    /** Posts read per venue and pass. 25 is one page of Business Discovery's media edge. */
    val mediaLimit: Int = DEFAULT_MEDIA_LIMIT,
    /** Older posts never go to the model. Their events are past, and a first read would spend the cap on them. */
    val lookbackDays: Long = DEFAULT_LOOKBACK_DAYS,
    val timeout: Duration = Duration.ofSeconds(DEFAULT_TIMEOUT_SECONDS),
    /** The Anthropic key. `application.yaml` fills it from `app.anthropic.api-key`. Empty means no caption is sent. */
    val apiKey: String = "",
    /** Haiku by default (ADR-041 rule 2). */
    val model: String = "claude-haiku-4-5",
    val modelTimeout: Duration = Duration.ofSeconds(DEFAULT_MODEL_TIMEOUT_SECONDS),
    /** Zero in tests, so a deliberate error response does not back off. */
    val maxRetries: Int = DEFAULT_MAX_RETRIES,
    /** Model calls of one pass over every venue. A post over the cap waits for the next pass. */
    val maxPerRun: Int = DEFAULT_MAX_PER_RUN,
    /** An event the model is less sure of is not drafted. */
    val minConfidence: Double = DEFAULT_MIN_CONFIDENCE,
    /** When the daily pass runs. Spring's six fields, in UTC. */
    val cron: String = DEFAULT_CRON,
    /** A draft nobody judged in this many days is deleted with its caption (ADR-048 rule 6). */
    val pendingDays: Long = DEFAULT_PENDING_DAYS
) {
    companion object {
        /** 04:30 UTC: after the 04:00 classification pass, so the two do not share the model's rate limit. */
        const val DEFAULT_CRON = "0 30 4 * * *"

        private const val DEFAULT_PENDING_DAYS = 14L
        private const val DEFAULT_MEDIA_LIMIT = 25
        private const val DEFAULT_LOOKBACK_DAYS = 30L
        private const val DEFAULT_TIMEOUT_SECONDS = 30L
        private const val DEFAULT_MODEL_TIMEOUT_SECONDS = 60L
        private const val DEFAULT_MAX_RETRIES = 2
        private const val DEFAULT_MAX_PER_RUN = 50
        private const val DEFAULT_MIN_CONFIDENCE = 0.7
    }
}
