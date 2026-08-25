package de.norm.events.scraper.klunkerkranich

import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventImporter
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.FetchResult
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.ImportResult
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.VenueLimitations
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import java.time.Clock

/**
 * Website importer for Klunkerkranich, the rooftop culture garden above the Neukölln Arcaden, on
 * WordPress.
 *
 * The pipeline is listing → event page, but not the merge
 * [de.norm.events.scraper.AbstractTwoPageWebsiteImporter] performs:
 * 1. Fetch the `/events/` programme via [HtmlFetcher] with conditional-request support
 *    (ETag / Last-Modified).
 * 2. Parse every `article.o-card` via [KlunkerkranichOverviewPageScraper], which supplies the
 *    title, date, start time, thumbnail and billed acts.
 * 3. Fetch each night's `/events/<slug>` page for the three fields the listing omits — the blurb,
 *    the entry charge and the full-size poster ([KlunkerkranichDetailPageScraper]).
 *
 * Step 3 is why this class implements [EventImporter] directly: the event page restates the
 * listing's own fields and adds only those three, so it is not the primary source the abstract base
 * class's detail scraper is expected to be. An event page that cannot be fetched or parsed is not
 * fatal — that night keeps its listing data, losing only the blurb, the price and the larger image.
 *
 * **What the source does not carry** is declared in [KLUNKERKRANICH_LIMITATIONS].
 *
 * **The programme is a short rolling horizon.** The venue publishes about ten days ahead and its
 * listing pagination is a no-op — `/events/page/2/` serves the same nights as page 1 — so one fetch
 * is the whole published programme, and an import stores far fewer events than a venue that
 * announces a season. That is the source being truthful about what it has announced, not a gap in
 * the parsing; OHM's importer has the same shape.
 *
 * @see KlunkerkranichOverviewPageScraper for the listing parsing logic.
 * @see KlunkerkranichDetailPageScraper for the blurb, price and poster.
 * @see <a href="https://klunkerkranich.org/events/">Klunkerkranich programme</a>
 */
@Component
class KlunkerkranichWebsiteImporter(
    private val htmlFetcher: HtmlFetcher,
    /** Clock for the listing scraper's year inference. Defaults to the system clock; override in tests. */
    clock: Clock = Clock.systemDefaultZone()
) : EventImporter {
    private val logger = KotlinLogging.logger {}

    override val eventSource: EventSource = EventSource.KLUNKERKRANICH

    private val overviewPageScraper = KlunkerkranichOverviewPageScraper(clock)
    private val detailPageScraper = KlunkerkranichDetailPageScraper()

    override suspend fun importEvents(
        url: String,
        etag: String?,
        lastModified: String?
    ): ImportResult =
        when (val fetchResult = htmlFetcher.fetch(url, etag, lastModified)) {
            is FetchResult.NotModified -> {
                ImportResult.NotModified
            }

            is FetchResult.Success -> {
                val events = overviewPageScraper.scrape(fetchResult.document, url)
                logger.info { "Scraped ${events.size} event(s) from the Klunkerkranich programme" }

                ImportResult.Success(
                    events = events.map { addEventPageFields(it) },
                    etag = fetchResult.etag,
                    lastModified = fetchResult.lastModified
                )
            }
        }

    /**
     * Fetches one event page for its blurb, price and full-size poster, degrading to the listing
     * data so a broken page costs only those three fields.
     */
    @Suppress("TooGenericExceptionCaught") // Intentional: a broken event page must not fail the whole import
    private suspend fun addEventPageFields(event: ScrapedEvent): ScrapedEvent =
        try {
            val document = htmlFetcher.fetchDocument(event.sourceUrl)
            val (priceBoxOffice, priceNote) = detailPageScraper.scrapePrice(document)
            event.copy(
                description = detailPageScraper.scrapeDescription(document, event.title),
                imageUrl = detailPageScraper.scrapeImageUrl(document) ?: event.imageUrl,
                priceBoxOffice = priceBoxOffice,
                priceNote = priceNote
            )
        } catch (e: Exception) {
            logger.warn(e) { "Failed to fetch event page for '${event.title}' (${event.sourceUrl}), keeping listing data" }
            event
        }
}

val KLUNKERKRANICH_LIMITATIONS =
    VenueLimitations(
        EventSource.KLUNKERKRANICH,
        AcceptedLimitation(
            LimitedAspect.EVENT_TYPE,
            "the venue publishes no category, so every night is stored as a party — which mislabels the occasional concert"
        ),
        AcceptedLimitation(LimitedAspect.DOORS_TIME, "the venue states when the roof opens, not when a show starts"),
        AcceptedLimitation(LimitedAspect.GENRE, "nothing on the site names a genre"),
        AcceptedLimitation(
            LimitedAspect.TICKET_URL,
            "entry is paid at the door; an occasional advance-RSVP link is written into a blurb rather than published as a field"
        ),
        AcceptedLimitation(LimitedAspect.SOLD_OUT, "nothing flags a night sold out"),
        AcceptedLimitation(LimitedAspect.CANCELLATION, "nothing flags a night cancelled")
    )
