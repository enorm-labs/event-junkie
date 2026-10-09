package de.norm.events.venuecheck

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Starts [VenueSiteCheckService] once a month. Off in tests (`app.scheduling.enabled: false`), which call the
 * service directly.
 */
@Component
@ConditionalOnProperty(name = ["app.scheduling.enabled"], havingValue = "true", matchIfMissing = true)
class VenueSiteCheckSchedule(
    private val service: VenueSiteCheckService
) {
    /** 05:15 UTC on the 1st, after the nightly sweeps. The service logs and swallows a failed pass. */
    @Scheduled(cron = $$"${app.venues.site-check-cron:0 15 5 1 * *}")
    suspend fun run() {
        service.runOnce()
    }
}
