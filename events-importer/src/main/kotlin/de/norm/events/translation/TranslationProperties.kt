package de.norm.events.translation

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * Configuration for machine translation of event descriptions.
 *
 * Bound to `app.translation`. **Defaults to off**, so nothing leaves the platform and nothing is
 * billed until an operator sets both [engine] and a key. ADR-026 also requires a per-source grant,
 * which is data rather than configuration, so switching an engine on translates nothing by itself.
 */
@ConfigurationProperties(prefix = "app.translation")
data class TranslationProperties(
    /** `none` or `anthropic`. Selects which [TranslationEngine] bean the context holds. */
    val engine: String = "none",
    /**
     * The API key, from the environment. Empty means the engine declines every request.
     *
     * Never written to a log or to a row. It reaches the process as `APP_TRANSLATION_API_KEY`, from
     * a Kubernetes Secret.
     */
    val apiKey: String = "",
    /**
     * The model, when [engine] is `anthropic`.
     *
     * ADR-026's cost table recommends Haiku for this: the corpus is under a million characters a
     * month and the task is a translation with a rule attached, not a judgement.
     */
    val model: String = "claude-haiku-4-5",
    /** How long one translation may take before it is abandoned. An import must not wait on it. */
    val timeout: Duration = Duration.ofSeconds(DEFAULT_TIMEOUT_SECONDS),
    /**
     * How often one call is retried before it is given up.
     *
     * Retrying here rather than waiting for the next import, which is a day away. Zero in tests, so
     * a deliberate error response does not spend the whole run backing off.
     */
    val maxRetries: Int = DEFAULT_MAX_RETRIES,
    /**
     * The most descriptions one source may translate in one of its imports. Per source, not per cycle:
     * a night over every granted source is bounded at this times the source count.
     *
     * A source that grants translation and then publishes 300 events would otherwise spend the whole
     * budget in one import. The remainder is picked up by the next one, because an untranslated event
     * stays a candidate.
     */
    val maxPerRun: Int = DEFAULT_MAX_PER_RUN
) {
    companion object {
        private const val DEFAULT_TIMEOUT_SECONDS = 60L
        private const val DEFAULT_MAX_RETRIES = 2
        private const val DEFAULT_MAX_PER_RUN = 50
    }
}
