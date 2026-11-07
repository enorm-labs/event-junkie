package de.norm.events.transit

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/** Configuration for the station import (#2724), bound to `app.transit` in `application.yaml`. */
@ConfigurationProperties(prefix = "app.transit")
data class TransitProperties(
    /** VBB's GTFS feed, published under CC BY 4.0. A zip of about 85 MB. */
    val gtfsUrl: String = "https://unternehmen.vbb.de/gtfs",
    /** How long the whole download may take. */
    val downloadTimeout: Duration = Duration.ofMinutes(DEFAULT_DOWNLOAD_TIMEOUT_MINUTES),
    /**
     * Fewer stations than this and the import keeps the stored ones. VBB's feed names about 1,000; a feed that
     * lost its trams or its S-Bahn must not empty the venue pages.
     */
    val minStations: Int = DEFAULT_MIN_STATIONS
) {
    companion object {
        private const val DEFAULT_DOWNLOAD_TIMEOUT_MINUTES = 10L
        private const val DEFAULT_MIN_STATIONS = 300
    }
}
