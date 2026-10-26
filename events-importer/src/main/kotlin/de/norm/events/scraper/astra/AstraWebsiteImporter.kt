package de.norm.events.scraper.astra

import de.norm.events.event.EventType
import de.norm.events.scraper.AbstractTwoPageWebsiteImporter
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.ScrapedField
import de.norm.events.scraper.VenueLimitations
import org.springframework.stereotype.Component

/**
 * Website importer for Astra Kulturhaus' Kulturhäuser-platform event listing.
 *
 * 1. [HtmlFetcher] fetches the overview (the homepage `/`) conditionally (ETag / Last-Modified).
 * 2. [AstraOverviewPageScraper] parses the articles — primary for event type and sold-out
 * status, discovery + fallback for every other field.
 * 3. Each detail page via [HtmlFetcher].
 * 4. [AstraDetailPageScraper] — primary for promoter, prices, ticket URL and description.
 *
 * @see AstraOverviewPageScraper for overview page parsing (event type, discovery, fallback)
 * @see AstraDetailPageScraper for detail page parsing (promoter, prices, ticket, description)
 * @see <a href="https://www.astra-berlin.de/">Astra Kulturhaus</a>
 */
@Component
class AstraWebsiteImporter(
    htmlFetcher: HtmlFetcher
) : AbstractTwoPageWebsiteImporter(htmlFetcher, AstraOverviewPageScraper()::scrape, AstraDetailPageScraper()::scrape) {
    /** The listing carries the banner notice alone; the event page adds the prose (#2505). */
    override val detailPageOwns: Set<ScrapedField> = setOf(ScrapedField.IMAGE, ScrapedField.DESCRIPTION)

    override val eventSource: EventSource = EventSource.ASTRA
    override val listsWholeProgramme: Boolean = true

    /**
     * Merges detail-page data ([primary]) with overview data ([fallback]). The detail page is
     * authoritative for promoter, prices, ticket URL and description; the overview for event type
     * and artists, and it supplies the date for the dateless featured teaser.
     *
     * The detail page *can* carry a `kind` label, but it is the raw per-day value without
     * [AstraOverviewPageScraper]'s festival-day normalization, so the overview type wins and the
     * detail value is only a fallback when the overview had no label. A `Sonstiges` night whose prose
     * calls it a party is one: `BINGO LOCO`, "the best bingo party" (#4009).
     */
    override fun fillGapsFromOverview(
        primary: ScrapedEvent,
        fallback: ScrapedEvent
    ): ScrapedEvent =
        primary.withGapsFrom(fallback).let { merged ->
            // Overview is authoritative for the type (it normalizes mislabeled festival days); the detail
            // kind only when the overview had no label.
            merged.copy(eventType = astraEventType(fallback.eventType ?: primary.eventType, merged.description))
        }
}

private val PARTY_PROSE = Regex("""\bparty\b""", RegexOption.IGNORE_CASE)

/** [type], or `PARTY` for an `OTHER` night whose [description] calls it a party. */
internal fun astraEventType(
    type: String?,
    description: String?
): String? = if (type == EventType.OTHER.name && PARTY_PROSE.containsMatchIn(description.orEmpty())) EventType.PARTY.name else type

val ASTRA_LIMITATIONS =
    VenueLimitations(
        EventSource.ASTRA,
        AcceptedLimitation(LimitedAspect.START_TIME, "some nights print only their doors, `Einlass 21:00`"),
        AcceptedLimitation(
            LimitedAspect.IMAGE,
            "a night an outside presenter books can carry only the presenter's logo, and no poster"
        ),
        AcceptedLimitation(LimitedAspect.GENRE, "the event page carries no genre field"),
        AcceptedLimitation(LimitedAspect.PRICE, "some nights print no figure and only link the promoter's outside ticket shop")
    )
