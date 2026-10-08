package de.norm.events.scraper.jonnyknuppel

import de.norm.events.scraper.AbstractSinglePageWebsiteImporter
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.VenueLimitations
import org.springframework.stereotype.Component

/**
 * Website importer for Jonny Knüppel, a static Astro site whose homepage menu holds the whole calendar.
 *
 * One request per import. Each night opens an overlay on the same page with its blurb, its `Line-up`
 * list and, for a few nights, an `Eintritt` price, so there is no page per night to fetch. The menu
 * splits the nights into `Vorschau` and `Rückschau` when the site is built, and a script moves them
 * in the browser later. Both lists are read, and the import drops the nights already past.
 *
 * The venue's `closed_on` is `2026-10-31` (ADR-046), so the source is useful only until then.
 *
 * @see JonnyKnuppelOverviewPageScraper for the parsing.
 */
@Component
class JonnyKnuppelWebsiteImporter(
    htmlFetcher: HtmlFetcher
) : AbstractSinglePageWebsiteImporter(htmlFetcher, "Jonny Knüppel", JonnyKnuppelOverviewPageScraper()::scrape) {
    override val eventSource: EventSource = EventSource.JONNY_KNUPPEL
    override val listsWholeProgramme: Boolean = true
}

val JONNY_KNUPPEL_LIMITATIONS =
    VenueLimitations(
        EventSource.JONNY_KNUPPEL,
        AcceptedLimitation(LimitedAspect.PER_EVENT_PAGE, "every night is an overlay on the homepage"),
        AcceptedLimitation(LimitedAspect.IMAGE, "the calendar shows no flyer"),
        AcceptedLimitation(LimitedAspect.TICKET_URL, "the site links no ticket shop"),
        AcceptedLimitation(LimitedAspect.DOORS_TIME, "the site prints one opening time per night"),
        AcceptedLimitation(LimitedAspect.GENRE, "the calendar names no style"),
        AcceptedLimitation(LimitedAspect.PRICE, "only a few nights print an entry price"),
        AcceptedLimitation(LimitedAspect.PROMOTERS, "the collective of a night is named only in its title"),
        AcceptedLimitation(LimitedAspect.ARTISTS, "about half the nights list no line-up"),
        AcceptedLimitation(LimitedAspect.DESCRIPTION, "about half the nights carry no blurb")
    )
