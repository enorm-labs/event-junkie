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
    /** A system user token of the business portfolio. A Graph Explorer token expires within hours. */
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
    val minConfidence: Double = DEFAULT_MIN_CONFIDENCE
) {
    private companion object {
        const val DEFAULT_MEDIA_LIMIT = 25
        const val DEFAULT_LOOKBACK_DAYS = 30L
        const val DEFAULT_TIMEOUT_SECONDS = 30L
        const val DEFAULT_MODEL_TIMEOUT_SECONDS = 60L
        const val DEFAULT_MAX_RETRIES = 2
        const val DEFAULT_MAX_PER_RUN = 50
        const val DEFAULT_MIN_CONFIDENCE = 0.7
    }
}
