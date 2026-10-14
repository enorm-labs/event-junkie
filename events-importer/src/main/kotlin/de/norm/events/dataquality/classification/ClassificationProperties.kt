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
    /** Creates the check, the model client, the nightly pass and the admin routes' backing beans. Nothing else switches it. */
    val enabled: Boolean = false,
    /**
     * The Anthropic key. `application.yaml` fills it from `app.anthropic.api-key`, which translation
     * reads too. Empty means the check sends nothing.
     */
    val apiKey: String = "",
    /** Haiku by default. A move to Sonnet needs a measured false-positive rate first (ADR-041 rule 2, #2997). */
    val model: String = "claude-haiku-4-5",
    /** The most model calls one run makes, a whole nightly pass included. An event over the cap stays a candidate. */
    val maxPerRun: Int = DEFAULT_MAX_PER_RUN,
    val timeout: Duration = Duration.ofSeconds(DEFAULT_TIMEOUT_SECONDS),
    /** Zero in tests, so a deliberate error response does not back off. */
    val maxRetries: Int = DEFAULT_MAX_RETRIES,
    /** When [ClassificationRunner] starts the nightly pass. Spring's six fields, in UTC. */
    val cron: String = DEFAULT_CRON,
    /**
     * Input plus output tokens the check may use in one calendar month (UTC). At the limit every run
     * stops before its next model call. The Anthropic workspace limit of ADR-041 stays the backstop.
     */
    val monthlyTokenBudget: Long = DEFAULT_MONTHLY_TOKEN_BUDGET,
    val minConfidence: MinConfidence = MinConfidence()
) {
    /**
     * The confidence an answer needs, per field, to become a suggestion. Below it the answer is a guess,
     * and a guess is the busywork #474 warns about. #2997 measured the floors (ADR-041).
     */
    data class MinConfidence(
        /** At 0.85, 10 of 11 type suggestions in #2997's sample were right. At 0.7, 16 of 34 were. */
        val eventType: Double = HIGH_FLOOR,
        val artists: Double = HIGH_FLOOR,
        val features: Double = DEFAULT_FLOOR,
        val startTime: Double = DEFAULT_FLOOR,
        /** The floor of `pricePresale` and `priceBoxOffice`. */
        val prices: Double = DEFAULT_FLOOR
    )

    companion object {
        /** 04:00 UTC: after the 03:00 snapshot that sets the regression gauge and the sweeps up to 03:45. */
        const val DEFAULT_CRON = "0 0 4 * * *"

        private const val DEFAULT_MAX_PER_RUN = 50
        private const val DEFAULT_TIMEOUT_SECONDS = 60L
        private const val DEFAULT_MAX_RETRIES = 2

        /** Fifty calls a night for a month at about 2,000 tokens each. */
        private const val DEFAULT_MONTHLY_TOKEN_BUDGET = 3_000_000L

        private const val DEFAULT_FLOOR = 0.7
        private const val HIGH_FLOOR = 0.85
    }
}
