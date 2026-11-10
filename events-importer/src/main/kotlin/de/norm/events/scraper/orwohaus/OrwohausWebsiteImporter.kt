package de.norm.events.scraper.orwohaus

import de.norm.events.common.BERLIN
import de.norm.events.scraper.AbstractJsonApiImporter
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.ApiClient
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.VenueLimitations
import org.springframework.stereotype.Component
import java.net.URI
import java.time.Clock

/**
 * Website importer for the ORWOhaus, a WordPress site with the Events Manager plugin. The
 * `/veranstaltungen/` listing pages ten rows at a time and names no category, so the importer
 * reads the plugin's `/events.ics` feed instead: every event in one response, with its categories,
 * description, poster and room. The configured URL stays the listing, the venue's real entry
 * point; the feed is resolved against its host. No conditional request, because the horizon moves
 * with the clock.
 *
 * @see OrwohausCalendarScraper for the parsing.
 * @see <a href="https://www.orwohaus.de/veranstaltungen/">ORWOhaus Veranstaltungen</a>
 */
@Component
class OrwohausWebsiteImporter(
    apiClient: ApiClient,
    /** Clock anchoring the horizon; override in tests. */
    clock: Clock = Clock.system(BERLIN)
) : AbstractJsonApiImporter(apiClient, "ORWOhaus", { calendar, _ -> OrwohausCalendarScraper(clock).scrape(calendar) }) {
    override val eventSource: EventSource = EventSource.ORWOHAUS

    override fun requestUrl(url: String): String = URI(url).resolve(FEED_PATH).toString()

    private companion object {
        const val FEED_PATH = "/events.ics"
    }
}

val ORWOHAUS_LIMITATIONS =
    VenueLimitations(
        EventSource.ORWOHAUS,
        AcceptedLimitation(LimitedAspect.PRICE, "prices appear only in the prose, and a house night often names none", verified = "2026-10-16"),
        AcceptedLimitation(LimitedAspect.DOORS_TIME, "a doors time appears only in the prose of a few nights", verified = "2026-10-16"),
        AcceptedLimitation(
            LimitedAspect.TICKET_URL,
            "a night names no shop of its own; the page's ticket links belong to other nights",
            verified = "2026-11-07"
        ),
        AcceptedLimitation(
            LimitedAspect.ARTISTS,
            "only a line-up list in the prose is read; other nights name acts in running text or run them together on one line, and a series title names none",
            verified = "2026-10-30"
        ),
        AcceptedLimitation(LimitedAspect.GENRE, "the feed files most nights by room or series and names a style on few", verified = "2026-10-19"),
        AcceptedLimitation(LimitedAspect.PROMOTERS, "an outside promoter is named only in the prose", verified = "2026-10-16"),
        AcceptedLimitation(LimitedAspect.SOLD_OUT, "the feed marks no night sold out", verified = "2026-10-16"),
        AcceptedLimitation(LimitedAspect.CANCELLATION, "the feed marks no night cancelled or moved", verified = "2026-10-16")
    )
