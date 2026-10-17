package de.norm.events.scraper.volksbuehne

import de.norm.events.scraper.AbstractSinglePageWebsiteImporter
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.SecondLanguagePage
import de.norm.events.scraper.SharedPages
import de.norm.events.scraper.VenueLimitations
import org.springframework.stereotype.Component

/**
 * Shared importer for the two Volksbühne houses, a Wagtail site whose home page is the whole
 * published Spielplan, server-rendered: theatre, dance, concerts, talks and films on every stage.
 * The concert page (`/konzerte/`) is a filtered view of the same rows. Each house reads the home
 * page and keeps its own stages ([VolksbuehneHouse]).
 *
 * Each row links its own performance page, read once for the text, the image and the schema.org
 * type ([SharedPages]). The site is translated by hand, and the English page is the second
 * language. The filter links (`?genre=`, `?access=zum-mitmachen`) tag too loosely to type or
 * drop a row: the participation filter also lists a play and a song salon.
 *
 * @see VolksbuehneSpielplanScraper for the home page.
 * @see VolksbuehneEventPageScraper for the performance page.
 */
@Suppress("AbstractClassCanBeConcreteClass") // A base for the house importers below it; an instance of it alone names no venue.
abstract class AbstractVolksbuehneHouseImporter(
    htmlFetcher: HtmlFetcher,
    house: VolksbuehneHouse
) : AbstractSinglePageWebsiteImporter(htmlFetcher, house.venueName, VolksbuehneSpielplanScraper(house)::scrape) {
    override val eventSource: EventSource = house.eventSource

    override val sharedPages: SharedPages<VolksbuehneEventPage> =
        VolksbuehneEventPageScraper().let { page ->
            SharedPages(page::scrape, VolksbuehneEventPage::applyTo, secondLanguage = SecondLanguagePage("en", page::description))
        }
}

/** The Volksbühne am Rosa-Luxemburg-Platz, with its salons. */
@Component
class VolksbuehneWebsiteImporter(
    htmlFetcher: HtmlFetcher
) : AbstractVolksbuehneHouseImporter(htmlFetcher, VolksbuehneHouse.ROSA_LUXEMBURG_PLATZ)

/** The Prater on Kastanienallee. */
@Component
class VolksbuehnePraterWebsiteImporter(
    htmlFetcher: HtmlFetcher
) : AbstractVolksbuehneHouseImporter(htmlFetcher, VolksbuehneHouse.PRATER)

val VOLKSBUEHNE_LIMITATIONS =
    VenueLimitations(
        sources = setOf(EventSource.VOLKSBUEHNE, EventSource.VOLKSBUEHNE_PRATER),
        limitations =
            listOf(
                AcceptedLimitation(
                    LimitedAspect.ARTISTS,
                    "a staged work's lines name a director, an author or a cast alike, so only concerts and parties bill acts"
                ),
                AcceptedLimitation(LimitedAspect.DOORS_TIME, "the programme gives one start time per performance"),
                AcceptedLimitation(LimitedAspect.END_TIME, "the performance page's end time is a 23:00 placeholder on many pages"),
                AcceptedLimitation(LimitedAspect.IMAGE, "many performance pages show no picture"),
                AcceptedLimitation(LimitedAspect.GENRE, "the venue names a format such as concert or dance, never a style"),
                AcceptedLimitation(LimitedAspect.PRICE, "neither the programme nor the performance page prints a price"),
                AcceptedLimitation(LimitedAspect.PROMOTERS, "the venue produces or presents every performance itself")
            )
    )
