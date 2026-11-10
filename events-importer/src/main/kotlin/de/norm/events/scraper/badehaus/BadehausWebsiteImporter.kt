package de.norm.events.scraper.badehaus

import de.norm.events.event.ArtistRole
import de.norm.events.event.EventType
import de.norm.events.scraper.AbstractTwoPageWebsiteImporter
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.ScrapedField
import de.norm.events.scraper.VenueLimitations
import de.norm.events.scraper.buildArtistsForEventType
import de.norm.events.scraper.isNonArtistName
import de.norm.events.slug.SlugGenerator
import org.springframework.stereotype.Component

/**
 * Website importer for Badehaus Berlin's WordPress `/events/` programme.
 *
 * Overview → detail:
 * 1. [HtmlFetcher] fetches `/events/` (ETag / Last-Modified); [BadehausOverviewPageScraper]
 * discovers every event — authoritative for the sold-out flag (a CSS class on the card), the
 * subtitle and the inferred event type, plus fallback title/date/doors/image.
 * 2. Each `/events/<slug>/` detail page via [BadehausDetailPageScraper] — primary for the full
 * description, the start time (`Beginn`) and the promoter, which the card omits.
 *
 * The configured source URL must point at `/events/` (the homepage is a separate landing page).
 *
 * @see BadehausOverviewPageScraper for discovery + the authoritative fields.
 * @see BadehausDetailPageScraper for the enriched per-event fields.
 * @see <a href="https://badehaus-berlin.com/events/">Badehaus Berlin programme</a>
 */
@Component
class BadehausWebsiteImporter(
    htmlFetcher: HtmlFetcher
) : AbstractTwoPageWebsiteImporter(htmlFetcher, BadehausOverviewPageScraper()::scrape, BadehausDetailPageScraper()::scrape) {
    /** The listing's type is a guess from the title; the event page names the venue's category (#2505). */
    override val detailPageOwns: Set<ScrapedField> = setOf(ScrapedField.IMAGE, ScrapedField.EVENT_TYPE, ScrapedField.ARTISTS)

    override val eventSource: EventSource = EventSource.BADEHAUS
    override val listsWholeProgramme: Boolean = true

    /**
     * Merges detail-page data ([primary]) with overview data ([fallback]). The detail page is
     * authoritative for what only it carries — description, start time, promoter, the category
     * and the status its notice announces. The overview is authoritative for the sold-out flag (the
     * card's CSS class) and the subtitle with the genre read from it; the rest, the status and the inferred type among them,
     * falls back to the overview only when the detail page did not supply it.
     *
     * **The artists follow the final type.** The overview builds them from the inferred type, so a
     * night the category calls a party would keep its title as a fake act (#1950); they are rebuilt
     * whenever the category decides the type ([categorisedArtists]).
     */
    override fun fillGapsFromOverview(
        primary: ScrapedEvent,
        fallback: ScrapedEvent
    ): ScrapedEvent =
        primary.withGapsFrom(fallback).copy(
            // The detail page has no roster: the artists come from the card's title and subtitle, typed
            // by the category when the page has one.
            artists =
                primary.eventType
                    ?.let { categorisedArtists(fallback.title, fallback.subtitle, it) }
                    ?: primary.artists.ifEmpty { fallback.artists }
        )

    /**
     * The acts of a card whose page names a category: a [nightLineup], else a [liveLineup], else the shared rule over title and
     * subtitle. A concert whose title names a `Night` bills only a lineup its subtitle lists, else nobody: the title is the night's
     * name (`Berlin Rap Night & Friends`, event 13319). The rule goes by the title, not the promoter, because bands promote their
     * own nights too.
     */
    private fun categorisedArtists(
        title: String,
        subtitle: String?,
        eventType: String
    ): List<ScrapedArtist> {
        val lineup = nightLineup(title, subtitle, eventType) ?: liveLineup(subtitle, eventType)
        return when {
            lineup != null -> lineup
            eventType == EventType.CONCERT.name && NIGHT_TITLE.containsMatchIn(title) -> emptyList()
            else -> buildArtistsForEventType(title, subtitle, eventType)
        }
    }

    /**
     * The acts of a concert night whose subtitle repeats the title and lists the acts after `feat`:
     * `Mehr Als Musik Clubshow` over `FREE Mehr Als Musik – Clubshow feat Joppe, Amelie, Ciez und JonE`. The title names the night, so it
     * bills no act. A `feat` after another name (`Butterwegge Solo feat Max Kupke`) is not this shape and keeps the title's billing.
     */
    private fun nightLineup(
        title: String,
        subtitle: String?,
        eventType: String
    ): List<ScrapedArtist>? {
        if (eventType != EventType.CONCERT.name) return null
        val match = subtitle?.let { NIGHT_FEAT.find(it) } ?: return null
        if (!SlugGenerator.slugify(match.groupValues[1]).endsWith(SlugGenerator.slugify(title))) return null
        return headliners(match.groupValues[2])
    }

    /**
     * The acts a concert's subtitle lists after `Live:`, over a title that names the night: `Live: Prophets of the Rising Dead, My
     * Sundown` under `Unholy Alliance Vol. IX` (event 13315).
     */
    private fun liveLineup(
        subtitle: String?,
        eventType: String
    ): List<ScrapedArtist>? {
        if (eventType != EventType.CONCERT.name) return null
        val acts = subtitle?.let { LIVE_LINEUP.find(it.trim()) }?.groupValues?.get(1) ?: return null
        return headliners(acts)
    }

    private fun headliners(acts: String): List<ScrapedArtist>? =
        acts
            .split(LIST_SEPARATOR)
            .map { it.trim() }
            .filterNot { it.isBlank() || isNonArtistName(it) }
            .map { ScrapedArtist(name = it, role = ArtistRole.HEADLINER) }
            .ifEmpty { null }

    private companion object {
        /** A subtitle that lists the night's acts after `Live:`. */
        val LIVE_LINEUP = Regex("""^live\s*:\s*(.+)$""", RegexOption.IGNORE_CASE)

        /** A title that names a night, not an act. */
        val NIGHT_TITLE = Regex("""\bnights?\b""", RegexOption.IGNORE_CASE)

        /** `<night> feat <acts>`; the night may carry a `FREE` prefix the title lacks. */
        val NIGHT_FEAT = Regex("""^(.+?)\s+(?:feat\.?|ft\.|featuring)\s+(.+)$""", RegexOption.IGNORE_CASE)

        /** A comma, or a spaced `und`, `and` or `&`, between two acts. */
        val LIST_SEPARATOR = Regex(""",\s*|\s+(?:und|and|&)\s+""", RegexOption.IGNORE_CASE)
    }
}

val BADEHAUS_LIMITATIONS =
    VenueLimitations(
        EventSource.BADEHAUS,
        AcceptedLimitation(
            LimitedAspect.START_TIME,
            "some pages print only the doors, `19:00 UHR` beside the date and at most an `Einlass` or `Doors` line below it, and no start"
        ),
        AcceptedLimitation(
            LimitedAspect.ARTISTS,
            "the venue publishes no roster; a concert bills its title and a Support: subtitle, or the acts a subtitle lists after feat or Live:, " +
                "and a concert titled as a night bills nobody when its subtitle lists no acts"
        ),
        AcceptedLimitation(
            LimitedAspect.PRICE,
            "the venue prints no figure, and where it names money at all it is a donation range the model has no field for, kept verbatim as the note"
        ),
        AcceptedLimitation(
            LimitedAspect.GENRE,
            "the subtitle names a style on some nights and is prose or a tour name on others, so about four in ten nights carry no genre"
        ),
        AcceptedLimitation(LimitedAspect.PROMOTERS, "the venue credits itself as the promoter on its own nights, so the stored promoter is the venue")
    )
