package de.norm.events.scraper.paloma

import de.norm.events.scraper.AbstractSinglePageWebsiteImporter
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.BERLIN
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.VenueLimitations
import org.springframework.stereotype.Component
import java.time.Clock

/**
 * Website importer for Paloma, a hand-coded PHP site. `programm.php` lists the whole programme,
 * to the end of the month, on one page with no API behind it ([PalomaProgrammPageScraper]).
 *
 * The bar holds one night a date and the site has no page per night, so the date is the night's identity.
 *
 * @see <a href="https://www.palomabar.de/programm.php">Paloma programme</a>
 */
@Component
class PalomaWebsiteImporter(
    htmlFetcher: HtmlFetcher,
    clock: Clock = Clock.system(BERLIN)
) : AbstractSinglePageWebsiteImporter(htmlFetcher, "the Paloma programme", PalomaProgrammPageScraper(clock)::scrape) {
    override val eventSource: EventSource = EventSource.PALOMA
    override val listsWholeProgramme: Boolean = true
}

val PALOMA_LIMITATIONS =
    VenueLimitations(
        EventSource.PALOMA,
        AcceptedLimitation(LimitedAspect.SUBTITLE, "each night has a date line, party names and DJs, no second title line"),
        AcceptedLimitation(LimitedAspect.DESCRIPTION, "the programme prints no text about a night"),
        AcceptedLimitation(LimitedAspect.EVENT_TYPE, "the site names no category; every night is a DJ night"),
        AcceptedLimitation(LimitedAspect.DOORS_TIME, "the site prints one start time per night"),
        AcceptedLimitation(LimitedAspect.END_TIME, "the site prints no end time"),
        AcceptedLimitation(LimitedAspect.IMAGE, "the programme carries no pictures"),
        AcceptedLimitation(LimitedAspect.TICKET_URL, "the site sells no tickets and links no ticket shop"),
        AcceptedLimitation(LimitedAspect.GENRE, "the site names no style per night"),
        AcceptedLimitation(LimitedAspect.PRICE, "the site prints no entry price"),
        AcceptedLimitation(LimitedAspect.PROMOTERS, "the site credits no promoter beside the party's name"),
        AcceptedLimitation(LimitedAspect.SOLD_OUT, "the site states no ticket status"),
        AcceptedLimitation(LimitedAspect.CANCELLATION, "the site has no status field, so a cancelled night can only disappear"),
        AcceptedLimitation(LimitedAspect.PER_EVENT_PAGE, "the site publishes no page per night, so every night points at the programme")
    )
