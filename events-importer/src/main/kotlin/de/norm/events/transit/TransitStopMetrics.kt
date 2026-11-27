package de.norm.events.transit

import de.norm.events.common.LastSuccess
import de.norm.events.common.LastSuccessGauge
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component

/**
 * The station import's one meter (#4596). `ej-transit-stops-stale` reads its age, because a failed import otherwise
 * leaves only an ERROR line, and no alert rule reads a log.
 */
@Component
class TransitStopMetrics(
    registry: MeterRegistry
) : LastSuccess by LastSuccessGauge(registry, LAST_SUCCESS) {
    companion object {
        /** `importer.transit_stops.import.last_success`, epoch seconds of the last [TransitStopImportService] import. */
        const val LAST_SUCCESS = "importer.transit_stops.import.last_success"
    }
}
