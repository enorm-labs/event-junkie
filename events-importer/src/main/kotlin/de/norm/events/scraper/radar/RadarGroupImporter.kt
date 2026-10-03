package de.norm.events.scraper.radar

import de.norm.events.scraper.ApiClient
import de.norm.events.scraper.EventImporter
import de.norm.events.scraper.ImportResult
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.hasFreeEntryPhrase
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * An importer for one radar.squat.net group, which is one venue's programme ([parseRadarEvents]).
 *
 * The configured `url` is the search API with the group's node id, the brackets encoded because
 * [ApiClient] hands it to `URI.create`: `…/api/1.2/search/events.json?facets%5Bgroup%5D%5B%5D=13&limit=500`.
 * One request returns the whole programme. The API sends validators, but [ApiClient] makes no
 * conditional request, so every run is a [ImportResult.Success]. `robots.txt` asks for 20 s between
 * requests, which the throttle honours (#2171).
 *
 * A venue that reads a lineup overrides [lineup]. The default bills no one: radar titles name the
 * night ("Konzert", "Geburtstagsgeballer") far more often than an act.
 */
abstract class RadarGroupImporter(
    private val apiClient: ApiClient
) : EventImporter {
    private val logger = KotlinLogging.logger {}

    override val listsWholeProgramme: Boolean = true

    /** The venue's name in log lines and warnings. */
    protected abstract val venueName: String

    /** The acts and the genre a row bills. */
    protected open fun lineup(event: RadarEvent): Lineup = Lineup()

    /** The row's event type; a venue whose radar category misnames its nights overrides it. */
    protected open fun eventType(event: RadarEvent): String? = event.eventType

    override suspend fun importEvents(
        url: String,
        etag: String?,
        lastModified: String?
    ): ImportResult {
        val listing = parseRadarEvents(apiClient.fetchJson(url), venueName)
        // A `count` above the rows read means the `limit` cut the programme short: the stale cleanup
        // would then delete real events past the last row.
        val complete = listing.count <= listing.rowsRead
        if (!complete) {
            logger.warn { "$venueName radar response holds ${listing.rowsRead} of ${listing.count} rows; raise the limit in the source url" }
        }
        val events = listing.events.map { toScrapedEvent(it) }
        logger.info { "Scraped ${events.size} event(s) from $venueName (${listing.rowsRead} radar row(s) read)" }
        return ImportResult.Success(events = events, etag = null, lastModified = null, complete = complete)
    }

    private fun toScrapedEvent(event: RadarEvent): ScrapedEvent {
        val lineup = lineup(event)
        return ScrapedEvent(
            title = event.title,
            description = event.description,
            eventType = eventType(event),
            eventDate = event.start.toLocalDate(),
            startTime = event.start.toLocalTime(),
            endDate = event.end?.toLocalDate(),
            endTime = event.end?.toLocalTime(),
            sourceUrl = event.url,
            sourceId = "${eventSource.sourceIdPrefix}${event.nodeId}",
            genre = lineup.genre,
            priceNote = event.price,
            free = hasFreeEntryPhrase(event.description),
            artists = lineup.artists
        )
    }

    /** What [lineup] reads off a row. */
    data class Lineup(
        val artists: List<ScrapedArtist> = emptyList(),
        val genre: String? = null
    )
}
