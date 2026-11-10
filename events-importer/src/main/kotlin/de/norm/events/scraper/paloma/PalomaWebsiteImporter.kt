package de.norm.events.scraper.paloma

import de.norm.events.common.BERLIN
import de.norm.events.scraper.AbstractSinglePageWebsiteImporter
import de.norm.events.scraper.AcceptedLimitation
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
        AcceptedLimitation(LimitedAspect.SUBTITLE, "each night has a date line, party names and DJs, no second title line", verified = "2026-10-16"),
        AcceptedLimitation(LimitedAspect.DESCRIPTION, "the programme prints no text about a night", verified = "2026-10-16"),
        AcceptedLimitation(LimitedAspect.EVENT_TYPE, "the site names no category; every night is a DJ night", verified = "2026-10-16"),
        AcceptedLimitation(LimitedAspect.DOORS_TIME, "the site prints one start time per night", verified = "2026-10-16"),
        AcceptedLimitation(LimitedAspect.END_TIME, "the site prints no end time", verified = "2026-10-16"),
        AcceptedLimitation(LimitedAspect.IMAGE, "the programme carries no pictures", verified = "2026-10-16"),
        AcceptedLimitation(LimitedAspect.TICKET_URL, "the site sells no tickets and links no ticket shop", verified = "2026-10-16"),
        AcceptedLimitation(LimitedAspect.GENRE, "the site names no style per night", verified = "2026-10-16"),
        AcceptedLimitation(LimitedAspect.PRICE, "the site prints no entry price", verified = "2026-10-16"),
        AcceptedLimitation(LimitedAspect.PROMOTERS, "the site credits no promoter beside the party's name", verified = "2026-10-16"),
        AcceptedLimitation(LimitedAspect.SOLD_OUT, "the site states no ticket status", verified = "2026-10-16"),
        AcceptedLimitation(LimitedAspect.CANCELLATION, "the site has no status field, so a cancelled night can only disappear", verified = "2026-10-16"),
        AcceptedLimitation(
            LimitedAspect.PER_EVENT_PAGE,
            "the site publishes no page per night, so every night points at the programme",
            verified = "2026-10-16"
        )
    )
