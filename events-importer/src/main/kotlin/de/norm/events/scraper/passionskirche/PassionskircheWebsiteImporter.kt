package de.norm.events.scraper.passionskirche

import de.norm.events.common.BERLIN
import de.norm.events.scraper.AbstractTwoPageWebsiteImporter
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.LogFields
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.ScrapedField
import de.norm.events.scraper.UNRESOLVED_EVENT_DATE
import de.norm.events.scraper.VenueLimitations
import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.oshai.kotlinlogging.Level
import org.springframework.stereotype.Component
import java.time.Clock

/**
 * Website importer for the Passionskirche, whose concerts Halle-Luja Kulturmanagement lists on an IONOS website builder
 * page with no API or feed. `/kommende-events` lists every upcoming concert on one page; each concert's own page adds
 * the text, the doors and start times and the ticket link.
 *
 * The listing's caption is free text and has been wrong: a date a month early, a start time two hours late. The event
 * page's schedule block owns the date and both times, and a disagreement is logged. Where that block names only
 * `Einlass`, the start time stays empty rather than taking the caption's time, which there is the doors time.
 *
 * @see PassionskircheOverviewPageScraper for the listing.
 * @see PassionskircheEventPageScraper for the event pages.
 */
@Component
class PassionskircheWebsiteImporter(
    htmlFetcher: HtmlFetcher,
    clock: Clock = Clock.system(BERLIN)
) : AbstractTwoPageWebsiteImporter(htmlFetcher, PassionskircheOverviewPageScraper(clock)::scrape, PassionskircheEventPageScraper()::scrape) {
    private val logger = KotlinLogging.logger {}

    override val eventSource: EventSource = EventSource.PASSIONSKIRCHE
    override val listsWholeProgramme: Boolean = true
    override val detailPageOwns: Set<ScrapedField> =
        setOf(
            ScrapedField.TITLE,
            ScrapedField.DESCRIPTION,
            ScrapedField.IMAGE,
            ScrapedField.START_TIME,
            ScrapedField.RUN_DATES,
            ScrapedField.ARTISTS
        )

    override fun fillGapsFromOverview(
        primary: ScrapedEvent,
        fallback: ScrapedEvent
    ): ScrapedEvent {
        if (primary.eventDate == UNRESOLVED_EVENT_DATE) return primary.withGapsFrom(fallback)
        if (primary.eventDate != fallback.eventDate) {
            logger.at(Level.WARN) {
                message = "Passionskirche: '${primary.title}' is listed on ${fallback.eventDate}, its page says ${primary.eventDate}; keeping the page"
                payload = mapOf(LogFields.URL to primary.sourceUrl, LogFields.EVENT_SOURCE_ID to primary.sourceId)
            }
        }
        return primary.withGapsFrom(fallback).copy(startTime = primary.startTime)
    }
}

val PASSIONSKIRCHE_LIMITATIONS =
    VenueLimitations(
        EventSource.PASSIONSKIRCHE,
        AcceptedLimitation(LimitedAspect.SUBTITLE, "the site prints one title line per concert"),
        AcceptedLimitation(LimitedAspect.GENRE, "the site files no category and names no style"),
        AcceptedLimitation(LimitedAspect.END_TIME, "the site states a doors and a start time, not an end"),
        AcceptedLimitation(LimitedAspect.PRICE, "the event pages name a price only rarely, as prose"),
        AcceptedLimitation(LimitedAspect.PROMOTERS, "the site credits no promoter"),
        AcceptedLimitation(LimitedAspect.SOLD_OUT, "the site states no ticket status"),
        AcceptedLimitation(LimitedAspect.CANCELLATION, "the site has no status field, so a cancelled concert can only disappear")
    )
