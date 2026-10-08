package de.norm.events.scraper.orangerie

import de.norm.events.scraper.AbstractSinglePageWebsiteImporter
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.VenueLimitations
import org.springframework.stereotype.Component

/**
 * Website importer for Orangerie Neukölln, a static one-pager whose programme section holds every announced night.
 *
 * The `?lang=en` page carries the same English event text, so there is no second language to read.
 *
 * @see OrangerieOverviewPageScraper for the parsing.
 * @see <a href="https://www.orangerie-nk.de/?lang=de#programm">Orangerie Neukölln programme</a>
 */
@Component
class OrangerieWebsiteImporter(
    htmlFetcher: HtmlFetcher
) : AbstractSinglePageWebsiteImporter(htmlFetcher, "Orangerie Neukölln", OrangerieOverviewPageScraper()::scrape) {
    override val eventSource: EventSource = EventSource.ORANGERIE_NEUKOELLN
    override val listsWholeProgramme: Boolean = true
}

val ORANGERIE_LIMITATIONS =
    VenueLimitations(
        EventSource.ORANGERIE_NEUKOELLN,
        AcceptedLimitation(LimitedAspect.PER_EVENT_PAGE, "the one-pager has no page per event; each night links out to Rausgegangen"),
        AcceptedLimitation(LimitedAspect.DOORS_TIME, "each night states one start time"),
        AcceptedLimitation(LimitedAspect.END_TIME, "the programme prints no closing time")
    )
