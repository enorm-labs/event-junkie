package de.norm.events.scraper.hau

import de.norm.events.scraper.AbstractSinglePageWebsiteImporter
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.SecondLanguagePage
import de.norm.events.scraper.SharedPages
import de.norm.events.scraper.VenueLimitations
import org.jsoup.nodes.Document
import org.springframework.stereotype.Component

/**
 * Shared importer for the three HAU Hebbel am Ufer houses, a TYPO3 site whose schedule is
 * server-rendered one month per page. The entry page is the current month; each later month is
 * the switcher's `prodMonth/MM-YYYY/` link, followed until the last published month links no next.
 * Every house reads the same months and keeps its own stages ([HauHouse]).
 *
 * Each performance links one page per production, read once for its text, poster and price
 * ([SharedPages]). The site is bilingual by hand, and the German production page is the second
 * language. HAU4 rows, online pieces and workshops drop (EVENT_SCOPE.md §3.2, §5).
 *
 * @see HauScheduleScraper for the month page.
 * @see HauProductionPageScraper for the production page.
 */
@Suppress("AbstractClassCanBeConcreteClass") // A base for the house importers below it; an instance of it alone names no venue.
abstract class AbstractHauHouseImporter(
    htmlFetcher: HtmlFetcher,
    house: HauHouse,
    private val scraper: HauScheduleScraper = HauScheduleScraper(house)
) : AbstractSinglePageWebsiteImporter(htmlFetcher, house.venueName, scraper::scrape) {
    override val eventSource: EventSource = house.eventSource
    override val maxListingPages: Int = MAX_MONTHS

    override val sharedPages: SharedPages<HauProduction> =
        HauProductionPageScraper().let { page ->
            SharedPages(page::scrape, HauProduction::applyTo, secondLanguage = SecondLanguagePage("de", page::description))
        }

    override fun nextListingPage(
        document: Document,
        url: String
    ): String? = scraper.nextMonthPage(document)

    private companion object {
        /** The venue publishes about three months ahead; a runaway guard on the switcher. */
        const val MAX_MONTHS = 6
    }
}

/** HAU1, the Hebbel-Theater on Stresemannstraße. */
@Component
class Hau1WebsiteImporter(
    htmlFetcher: HtmlFetcher
) : AbstractHauHouseImporter(htmlFetcher, HauHouse.HAU1)

/** HAU2 on Hallesches Ufer, with the WAU bar. */
@Component
class Hau2WebsiteImporter(
    htmlFetcher: HtmlFetcher
) : AbstractHauHouseImporter(htmlFetcher, HauHouse.HAU2)

/** HAU3 on Tempelhofer Ufer, with the Houseclub. */
@Component
class Hau3WebsiteImporter(
    htmlFetcher: HtmlFetcher
) : AbstractHauHouseImporter(htmlFetcher, HauHouse.HAU3)

val HAU_LIMITATIONS =
    VenueLimitations(
        sources = setOf(EventSource.HAU1, EventSource.HAU2, EventSource.HAU3),
        limitations =
            listOf(
                AcceptedLimitation(
                    LimitedAspect.ARTISTS,
                    "a staged work's line names an artist, a company or a series alike, so only concerts and DJ sets bill acts"
                ),
                AcceptedLimitation(LimitedAspect.DOORS_TIME, "only a few concerts print a doors time"),
                AcceptedLimitation(LimitedAspect.GENRE, "the venue names a format such as music or dance, never a style"),
                AcceptedLimitation(LimitedAspect.PRICE_PRESALE, "some production pages print no price"),
                AcceptedLimitation(LimitedAspect.PRICE_BOX_OFFICE, "one price per production, sold alike online and at the box office"),
                AcceptedLimitation(LimitedAspect.TICKET_URL, "the ticket buttons load by script for each date and are not in the page"),
                AcceptedLimitation(LimitedAspect.PROMOTERS, "the venue presents or co-produces every production itself")
            )
    )
