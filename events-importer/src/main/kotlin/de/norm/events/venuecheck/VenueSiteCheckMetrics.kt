package de.norm.events.venuecheck

import de.norm.events.common.LastSuccess
import de.norm.events.common.LastSuccessGauge
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component

/**
 * The site check's one meter (#3147). `ej-venue-site-check-stale` reads its age, because a failed pass otherwise
 * leaves only an ERROR line, and no alert rule reads a log.
 */
@Component
class VenueSiteCheckMetrics(
    registry: MeterRegistry
) : LastSuccess by LastSuccessGauge(registry, LAST_SUCCESS) {
    companion object {
        /** `importer.venues.site_check.last_success`, epoch seconds of the last finished [VenueSiteCheckService] pass. */
        const val LAST_SUCCESS = "importer.venues.site_check.last_success"
    }
}
