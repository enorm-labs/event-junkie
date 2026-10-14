package de.norm.events.dataquality.classification

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * Configuration for [EventClassificationCheck], bound to `app.data-quality.classification`.
 *
 * **Off by default**, so no event text leaves the platform and nothing is billed until an operator
 * sets [enabled] and a key (ADR-041).
 */
@ConfigurationProperties(prefix = "app.data-quality.classification")
data class ClassificationProperties(
    /** Creates the check, the model client and the admin routes' backing bean. Nothing else switches it. */
    val enabled: Boolean = false,
    /**
     * The Anthropic key. `application.yaml` reads the translation key's variable, so one Secret
     * serves both. Empty means the check sends nothing.
     */
    val apiKey: String = "",
    /** Haiku by default. A move to Sonnet needs a measured false-positive rate first (ADR-041 rule 2, #2997). */
    val model: String = "claude-haiku-4-5",
    /** The most model calls one run makes. An event over the cap stays a candidate for the next run. */
    val maxPerRun: Int = DEFAULT_MAX_PER_RUN,
    val timeout: Duration = Duration.ofSeconds(DEFAULT_TIMEOUT_SECONDS),
    /** Zero in tests, so a deliberate error response does not back off. */
    val maxRetries: Int = DEFAULT_MAX_RETRIES
) {
    private companion object {
        const val DEFAULT_MAX_PER_RUN = 50
        const val DEFAULT_TIMEOUT_SECONDS = 60L
        const val DEFAULT_MAX_RETRIES = 2
    }
}
