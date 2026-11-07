package de.norm.events.transit

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Starts [TransitStopImportService] once a quarter. Stations move seldom, and the feed is 85 MB. Off in tests
 * (`app.scheduling.enabled: false`), which call the service directly.
 */
@Component
@ConditionalOnProperty(name = ["app.scheduling.enabled"], havingValue = "true", matchIfMissing = true)
class TransitStopSchedule(
    private val service: TransitStopImportService
) {
    /** 04:45 UTC on the 2nd of January, April, July and October. The service logs and swallows a failed pass. */
    @Scheduled(cron = $$"${app.transit.import-cron:0 45 4 2 1,4,7,10 *}")
    suspend fun quarterly() {
        service.runOnce()
    }
}
