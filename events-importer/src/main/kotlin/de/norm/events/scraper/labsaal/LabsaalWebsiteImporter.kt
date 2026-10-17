package de.norm.events.scraper.labsaal

import de.norm.events.scraper.AbstractJsonApiImporter
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.ApiClient
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.VenueLimitations
import org.springframework.stereotype.Component
import java.net.URI

/**
 * Website importer for the LabSaal, a WordPress site with the Modern Events Calendar plugin. The
 * `/events/` agenda shows about a month, and its JSON-LD adds the UTC offset to each printed
 * time (`19:00` printed, `21:00+02:00` in the block). The plugin's iCalendar feed (`/?mec-ical-feed=1`)
 * carries the whole upcoming programme with the printed wall-clock times, its categories,
 * description and poster, so the importer reads that. The REST collection `mec-events` has no
 * dates. The configured URL stays the agenda; the feed is resolved against its host.
 *
 * @see LabsaalCalendarScraper for the parsing.
 * @see <a href="https://labsaal.de/events/">LabSaal Events</a>
 */
@Component
class LabsaalWebsiteImporter(
    apiClient: ApiClient
) : AbstractJsonApiImporter(apiClient, "LabSaal", { calendar, _ -> LabsaalCalendarScraper().scrape(calendar) }) {
    override val eventSource: EventSource = EventSource.LABSAAL
    override val listsWholeProgramme: Boolean = true

    override fun requestUrl(url: String): String = URI(url).resolve(FEED_PATH).toString()

    private companion object {
        const val FEED_PATH = "/?mec-ical-feed=1"
    }
}

val LABSAAL_LIMITATIONS =
    VenueLimitations(
        EventSource.LABSAAL,
        AcceptedLimitation(
            LimitedAspect.EVENT_TYPE,
            "the venue names no format, so the type comes from words in the title and description, and a night without one is typed other"
        ),
        AcceptedLimitation(LimitedAspect.ARTISTS, "the feed has no line-up field, and a title mixes an act's name with the programme's"),
        AcceptedLimitation(LimitedAspect.GENRE, "the feed names no style"),
        AcceptedLimitation(LimitedAspect.PRICE, "the feed has no price field; an event page prints unlabelled tiers, and the prose rarely names one"),
        AcceptedLimitation(LimitedAspect.DOORS_TIME, "a doors time appears only in the prose of a few nights"),
        AcceptedLimitation(LimitedAspect.END_TIME, "the feed fills an end for every night, also where the site hides it, so no end is taken"),
        AcceptedLimitation(LimitedAspect.END_DATE, "the feed fills an end for every night, also where the site hides it, so no end is taken"),
        AcceptedLimitation(LimitedAspect.PROMOTERS, "an outside organiser is named only in the prose"),
        AcceptedLimitation(LimitedAspect.SOLD_OUT, "the feed marks no night sold out"),
        AcceptedLimitation(LimitedAspect.CANCELLATION, "the feed marks no night cancelled or moved")
    )
