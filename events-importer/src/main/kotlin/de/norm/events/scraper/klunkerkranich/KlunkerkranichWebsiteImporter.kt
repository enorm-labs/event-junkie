package de.norm.events.scraper.klunkerkranich

import de.norm.events.scraper.AbstractSinglePageWebsiteImporter
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.VenueLimitations
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import java.time.Clock

/**
 * Website importer for Klunkerkranich, the rooftop culture garden above the Neukölln Arcaden,
 * on WordPress.
 *
 * Listing → event page, but not the merge
 * [de.norm.events.scraper.AbstractTwoPageWebsiteImporter] performs:
 * 1. [HtmlFetcher] fetches `/events/` conditionally (ETag / Last-Modified).
 * 2. [KlunkerkranichOverviewPageScraper] parses every `article.o-card` — title, date, start
 * time, thumbnail and billed acts.
 * 3. Each night's `/events/<slug>` page for the three fields the listing omits — blurb, entry
 * charge, full-size poster ([KlunkerkranichDetailPageScraper]).
 *
 * Step 3 runs in [postProcess] rather than through [de.norm.events.scraper.AbstractTwoPageWebsiteImporter]:
 * the event page restates the listing and adds only those three, so it is not the primary source
 * that base class's detail scraper must be. An unfetchable or unparseable event page is not fatal —
 * the night keeps its listing data, losing only blurb, price and larger image.
 *
 * **What the source does not carry** is declared in [KLUNKERKRANICH_LIMITATIONS].
 *
 * **The programme is a short rolling horizon.** About ten days ahead, and listing pagination
 * is a no-op — `/events/page/2/` serves the same nights as page 1 — so one fetch is the whole
 * published programme and an import stores far fewer events than a venue announcing a season.
 * The source is truthful about what it has announced, not a parsing gap; OHM has the same shape.
 *
 * @see KlunkerkranichOverviewPageScraper for the listing parsing logic.
 * @see KlunkerkranichDetailPageScraper for the blurb, price and poster.
 * @see <a href="https://klunkerkranich.org/events/">Klunkerkranich programme</a>
 */
@Component
class KlunkerkranichWebsiteImporter(
    private val htmlFetcher: HtmlFetcher,
    /** Clock for the listing scraper's year inference; override in tests. */
    clock: Clock = Clock.systemDefaultZone()
) : AbstractSinglePageWebsiteImporter(htmlFetcher, "the Klunkerkranich programme", KlunkerkranichOverviewPageScraper(clock)::scrape) {
    private val logger = KotlinLogging.logger {}

    override val eventSource: EventSource = EventSource.KLUNKERKRANICH
    override val fetchesBeyondEntryPage: Boolean = true

    private val detailPageScraper = KlunkerkranichDetailPageScraper()

    override suspend fun postProcess(events: List<ScrapedEvent>): List<ScrapedEvent> = events.map { addEventPageFields(it) }

    /**
     * Fetches one event page for blurb, price and full-size poster, degrading to the listing data
     * so a broken page costs only those three fields.
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
        AcceptedLimitation(LimitedAspect.GENRE, "nothing on the site names a genre; every night takes the house's House"),
        AcceptedLimitation(
            LimitedAspect.TICKET_URL,
            "entry is paid at the door; an occasional advance-RSVP link is written into a blurb rather than published as a field"
        ),
        AcceptedLimitation(LimitedAspect.SOLD_OUT, "nothing flags a night sold out"),
        AcceptedLimitation(LimitedAspect.CANCELLATION, "nothing flags a night cancelled"),
        AcceptedLimitation(
            LimitedAspect.ARTISTS,
            "a billing joined by `&` is split into two acts, the venue billing a duo and a pair of separate acts the same way"
        ),
        AcceptedLimitation(
            LimitedAspect.PRICE,
            "entry is a time-banded range (`5-9€`) the model has no field for, so the wording is kept verbatim as the note and only a lone figure is stored"
        ),
        houseGenre = "House"
    )
