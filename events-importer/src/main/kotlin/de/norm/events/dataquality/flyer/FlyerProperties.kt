package de.norm.events.dataquality.flyer

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Configuration for [FlyerLineupCheck], bound to `app.data-quality.flyer`.
 *
 * **Off by default.** It runs only beside the classification check, whose key, model and monthly token
 * budget it shares (ADR-041 rule 5, ADR-059).
 */
@ConfigurationProperties(prefix = "app.data-quality.flyer")
data class FlyerProperties(
    val enabled: Boolean = false,
    /** The source slugs whose flyers are read. Empty reads none (ADR-059 rule 1). */
    val sources: List<String> = emptyList(),
    /** The most flyers one nightly pass reads. An event over the cap waits for the next night. */
    val maxPerRun: Int = DEFAULT_MAX_PER_RUN,
    /** The width imgproxy renders the flyer at. Wide enough for small lineup print, small enough to keep the image tokens low. */
    val width: Int = DEFAULT_WIDTH
) {
    private companion object {
        const val DEFAULT_MAX_PER_RUN = 20
        const val DEFAULT_WIDTH = 1024
    }
}
