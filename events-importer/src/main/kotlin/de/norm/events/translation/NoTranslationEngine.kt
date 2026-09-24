package de.norm.events.translation

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

/**
 * The engine that translates nothing, and the default.
 *
 * **Every environment starts here.** Translating means sending a venue's text to a third party and
 * paying per character, so both have to be chosen: a key in the environment, and
 * `app.translation.engine` set to something else. An environment that sets neither behaves exactly
 * as it did before ADR-026.
 */
@Component
@ConditionalOnProperty(name = ["app.translation.engine"], havingValue = "none", matchIfMissing = true)
class NoTranslationEngine : TranslationEngine {
    private val logger = KotlinLogging.logger {}

    override val id = "none"

    override val enabled = false

    /** Logs once per call at DEBUG, so a grant that produces nothing is explainable without a restart. */
    override suspend fun translate(request: TranslationRequest): TranslationResult {
        logger.debug { "No translation engine is configured, so ${request.from.code}->${request.to.code} is skipped" }
        return TranslationResult.Failed
    }
}
