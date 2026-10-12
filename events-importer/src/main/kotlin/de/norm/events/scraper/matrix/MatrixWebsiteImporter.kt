package de.norm.events.scraper.matrix

import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventImporter
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.ImportResult
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.LogContext
import de.norm.events.scraper.LogFields
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.VenueLimitations
import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.oshai.kotlinlogging.Level
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import org.jsoup.nodes.Document
import org.springframework.stereotype.Component

/**
 * Website importer for Matrix Club Berlin — a club open every night, with one recurring format per
 * weekday. The site is a server-rendered Next.js app in German (`/de`), English (`/en`) and Spanish.
 *
 * The seeded `/party-in-berlin/` URL redirects to `/de`, which links one page per format,
 * `/de/night/<format>`. The importer reads those links rather than a fixed list, so a new format is
 * picked up. Each night page shows only the format's next date, so a run imports about seven
 * nights, at most one week ahead. A page that shows no date yields no night. A page that fails
 * costs that night only, and the run reports itself incomplete, so the stale cleanup keeps the
 * stored row.
 *
 * The only ticket link is the club's Eventbrite organiser page, so no night stores one.
 *
 * The importer reads no second language: the English pages' text matches the German closely
 * enough that the upsert stores none.
 *
 * Conditional requests are not used: the site sends `Cache-Control: no-store` and neither ETag nor
 * Last-Modified.
 *
 * @see MatrixNightPageScraper for the per-night parsing.
 * @see <a href="https://www.matrix-berlin.de/de">Matrix home page</a>
 */
@Component
class MatrixWebsiteImporter(
    private val htmlFetcher: HtmlFetcher
) : EventImporter {
    private val logger = KotlinLogging.logger {}

    override val eventSource: EventSource = EventSource.MATRIX

    override val fetchesBeyondEntryPage: Boolean get() = true

    private val nightPageScraper = MatrixNightPageScraper()

    override suspend fun importEvents(
        url: String,
        etag: String?,
        lastModified: String?
    ): ImportResult {
        val nightUrls = nightPageUrls(htmlFetcher.fetchDocument(url))
        logger.info { "Found ${nightUrls.size} Matrix night page(s) on $url" }
        val nights = nightUrls.map { readNight(it) }
        val events = nights.mapNotNull { it?.event }
        logger.info { "Scraped ${events.size} Matrix event(s) from ${nightUrls.size} night page(s)" }
        return ImportResult.Success(
            events = events,
            etag = null,
            lastModified = null,
            complete = nightUrls.isNotEmpty() && nights.none { it == null }
        )
    }

    /** The distinct `/de/night/<format>` links on the home page, in page order. */
    private fun nightPageUrls(home: Document): List<String> =
        home
            .select("a[href]")
            .map { it.absUrl("href") }
            .filter { NIGHT_PAGE.containsMatchIn(it) }
            .distinct()

    /**
     * The page at [nightUrl], or null when the fetch or the parse throws; a failure logs one `WARN`.
     * A page that loads but shows no date is a [NightPage] without an event, so the run stays complete.
     */
    @Suppress("TooGenericExceptionCaught") // Intentional: one broken night page must not fail the whole import
    private suspend fun readNight(nightUrl: String): NightPage? =
        try {
            val document = htmlFetcher.fetchDocument(nightUrl)
            NightPage(withContext(LogContext.forPage(nightUrl)) { nightPageScraper.scrape(document, nightUrl) })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.at(Level.WARN) {
                message = "Failed to read a Matrix night page, skipping that night"
                cause = e
                payload = mapOf(LogFields.URL to nightUrl)
            }
            null
        }

    private companion object {
        /** A format page, `https://www.matrix-berlin.de/de/night/social`. */
        val NIGHT_PAGE = Regex("""/de/night/[^/?#]+/?$""")
    }
}

/** One night page as read: [event] is null when the page shows no next date. */
private class NightPage(
    val event: ScrapedEvent?
)

val MATRIX_LIMITATIONS =
    VenueLimitations(
        EventSource.MATRIX,
        AcceptedLimitation(LimitedAspect.TICKET_URL, "the only ticket link is the club's Eventbrite organiser page, never a per-night one"),
        AcceptedLimitation(LimitedAspect.DOORS_TIME, "the night states one `Einlass ab` time, which is taken as the start"),
        AcceptedLimitation(LimitedAspect.GENRE, "a night listed only by its weekday (`Matrix - Monday`) has no night page, so no genre"),
        AcceptedLimitation(LimitedAspect.PRICE, "the club publishes no door price; a night page carries at most a door offer, kept as the note")
    )
