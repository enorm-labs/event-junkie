package de.norm.events.scraper.wuehlmaeuse

import de.norm.events.common.BERLIN
import de.norm.events.event.ArtistRole
import de.norm.events.event.SpokenLanguage
import de.norm.events.scraper.AbstractWpRestImporter
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.ApiClient
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.VenueLimitations
import de.norm.events.scraper.WalkedListing
import de.norm.events.scraper.WpRestWalk
import de.norm.events.scraper.enrichFromSharedPages
import de.norm.events.scraper.isNonArtistName
import de.norm.events.scraper.querySeparator
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.LocalDate

/**
 * Website importer for Die Wühlmäuse, the Kabarett theatre at Theodor-Heuss-Platz, over its
 * WooCommerce Store API.
 *
 * The shop is the programme: every performance up to two years ahead is on sale, as one product per
 * price category, about 1,700 products for 400 performances. The Store API pages them a hundred at
 * a time with no cursor, so the walk asks for the next page until one comes back short.
 *
 * The products carry no show text. Each production's `/veranstaltung/<slug>/` page does, so each one
 * is read once and its text applied to every performance. A failed page flags its performances, so the
 * upsert keeps the stored text. A dated guest line goes only to its own performance, which bills the guests when it bills
 * no one else (Nightwash).
 *
 * @see WuehlmaeuseApiScraper for the product shape and the fold into one event per performance.
 * @see WuehlmaeuseProductionPageScraper for the show text.
 * @see <a href="https://wuehlmaeuse.de/">Die Wühlmäuse</a>
 */
@Component
class WuehlmaeuseWebsiteImporter(
    apiClient: ApiClient,
    private val htmlFetcher: HtmlFetcher,
    /** For the year-less guest lines; override in tests. */
    private val clock: Clock = Clock.system(BERLIN)
) : AbstractWpRestImporter<WuehlmaeuseTicket, WuehlmaeuseShopPage>(apiClient, WUEHLMAEUSE_PER_PAGE, MAX_PAGES) {
    override val eventSource: EventSource = EventSource.WUEHLMAEUSE
    override val fetchesBeyondEntryPage: Boolean = true

    private val apiScraper = WuehlmaeuseApiScraper()
    private val productionScraper = WuehlmaeuseProductionPageScraper()

    override fun walk(url: String): WpRestWalk<WuehlmaeuseTicket, WuehlmaeuseShopPage> =
        WpRestWalk({ page -> "$url${url.querySeparator()}per_page=$WUEHLMAEUSE_PER_PAGE&page=$page" }, apiScraper::scrapePage)

    override suspend fun toEvents(
        listing: WalkedListing<WuehlmaeuseTicket>,
        url: String
    ): List<ScrapedEvent> {
        val today = LocalDate.now(clock)
        return enrichFromSharedPages(htmlFetcher::fetchDocument, apiScraper.toEvents(listing.items), productionScraper::scrape, { production, event ->
            val guests = guestLineFor(event.eventDate, production.guests, today)
            event.copy(
                description = listOfNotNull(guests?.line, production.description).joinToString("\n").ifBlank { null },
                artists =
                    event.artists.ifEmpty {
                        guests
                            ?.names
                            .orEmpty()
                            .filterNot(::isNonArtistName)
                            .map { ScrapedArtist(name = it, role = ArtistRole.HEADLINER) }
                    }
            )
        })
    }

    override fun scrapedMessage(
        events: List<ScrapedEvent>,
        listing: WalkedListing<WuehlmaeuseTicket>
    ): String = "Scraped ${events.size} performance(s) from ${listing.items.size} Wühlmäuse ticket(s) across ${listing.pages} page(s)"

    private companion object {
        /** A runaway guard on the page walk; the shop runs to eighteen pages. */
        const val MAX_PAGES = 40
    }
}

/**
 * German is the house language because the programme is German in practice (#2584). On `2026-10-04` the
 * production API listed 402 upcoming shows under 177 titles, and no title named another language.
 */
val WUEHLMAEUSE_LIMITATIONS =
    VenueLimitations(
        EventSource.WUEHLMAEUSE,
        AcceptedLimitation(
            LimitedAspect.EVENT_TYPE,
            "the shop files every show under its act and names no format; Kabarett, comedy and music share one stage",
            verified = "2026-10-03"
        ),
        AcceptedLimitation(LimitedAspect.GENRE, "the shop names no genre", verified = "2026-10-03"),
        AcceptedLimitation(
            LimitedAspect.PRICE_BOX_OFFICE,
            "the shop sells one price per seat category and states no box-office price",
            verified = "2026-10-03"
        ),
        AcceptedLimitation(LimitedAspect.PROMOTERS, "the shop names no promoter", verified = "2026-10-04"),
        houseLanguage = SpokenLanguage.GERMAN
    )
