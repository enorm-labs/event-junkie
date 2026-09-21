package de.norm.events.scraper.insel

import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.ApiClient
import de.norm.events.scraper.EventImporter
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.GATSBY_STATIC_QUERY_HASHES
import de.norm.events.scraper.ImportResult
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.VenueLimitations
import de.norm.events.scraper.gatsbyPageDataUrl
import de.norm.events.scraper.gatsbyStaticQueryUrl
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper

/**
 * Website importer for Kulturhaus Insel Berlin, a Gatsby front end over DatoCMS whose homepage
 * is the programme (ADR-007 §"Selector Strategy" priority 1). The events are in a shared static
 * query, not the page's own `page-data.json`: Gatsby keys its artefact by a hash of the query
 * text (`/page-data/sq/d/3497155224.json`), neither guessable nor stable across a query edit, so
 * the hash is discovered: fetch the page's `page-data.json`, whose `staticQueryHashes` lists
 * every static query ([gatsbyPageDataUrl]), then hand each candidate to [InselApiScraper] until
 * one is the events query. That costs one extra request plus the candidates before the events
 * one (two of six at capture), where a pinned hash would break silently on the next
 * content-model change. The programme page is what the source stores and the events'
 * `sourceUrl` (ADR-007). No conditional request: the artefacts are regenerated on every rebuild.
 *
 * @see InselApiScraper for the JSON parsing logic.
 * @see <a href="https://www.inselberlin.de/">Kulturhaus Insel Berlin</a>
 */
@Component
class InselWebsiteImporter(
    private val apiClient: ApiClient
) : EventImporter {
    private val logger = KotlinLogging.logger {}

    override val eventSource: EventSource = EventSource.INSEL

    private val apiScraper = InselApiScraper()

    private val jsonMapper: JsonMapper = JsonMapper.builder().build()

    override suspend fun importEvents(
        url: String,
        etag: String?,
        lastModified: String?
    ): ImportResult {
        val events = fetchEvents(url)
        logger.info { "Scraped ${events.size} event(s) from Kulturhaus Insel Berlin" }

        // The artefacts' ETag / Last-Modified track the build; change detection relies on idempotent
        // upserts.
        return ImportResult.Success(events = events, etag = null, lastModified = null)
    }

    /**
     * Walks the page's static-query candidates and returns the events from the first artefact that
     * is the programme query, or an empty list.
     */
    @Suppress("ReturnCount") // The early exits for "no candidates" and "found it" are clearer than nesting.
    private suspend fun fetchEvents(url: String): List<ScrapedEvent> {
        val hashes = staticQueryHashes(apiClient.fetchJson(gatsbyPageDataUrl(url)))
        if (hashes.isEmpty()) {
            logger.warn { "Insel page-data lists no staticQueryHashes; no programme artefact to read" }
            return emptyList()
        }

        for (hash in hashes) {
            val events = apiScraper.scrape(apiClient.fetchJson(gatsbyStaticQueryUrl(url, hash)), url)
            if (events != null) return events
            logger.debug { "Insel static query $hash is not the programme artefact" }
        }
        logger.warn { "None of the ${hashes.size} Insel static queries carries the programme" }
        return emptyList()
    }

    /** The `staticQueryHashes` the page-data artefact lists, in the order Gatsby wrote them. */
    @Suppress("TooGenericExceptionCaught") // A malformed page-data body must degrade to no candidates, never abort the import.
    private fun staticQueryHashes(json: String): List<String> =
        try {
            jsonMapper
                .readTree(json)
                .path(GATSBY_STATIC_QUERY_HASHES)
                .mapNotNull { node -> node.asString().trim().takeIf { it.isNotBlank() } }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to parse Insel page-data for its static-query hashes" }
            emptyList()
        }
}

val INSEL_LIMITATIONS =
    VenueLimitations(
        EventSource.INSEL,
        AcceptedLimitation(LimitedAspect.PRICE, "the venue names no prices anywhere; only an Eintritt-frei note on the free Sunday matinées"),
        AcceptedLimitation(LimitedAspect.GENRE, "the venue publishes no genre"),
        AcceptedLimitation(LimitedAspect.CANCELLATION, "a dropped show is removed from the CMS rather than flagged"),
        AcceptedLimitation(LimitedAspect.PER_EVENT_PAGE, "every event points at the programme page and takes its identity from its date plus its title"),
        AcceptedLimitation(
            LimitedAspect.EVENT_TYPE,
            "the venue publishes no category, so a title that is an event name rather than an act is minted as a concert"
        ),
        AcceptedLimitation(
            LimitedAspect.ARTISTS,
            "a support act billed without a colon reads as prose, so only a colon or a line-leading support marker is followed"
        )
    )
