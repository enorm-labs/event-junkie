package de.norm.events.scraper.ritterbutzke

import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.UNRESOLVED_EVENT_DATE
import de.norm.events.scraper.attrAt
import de.norm.events.scraper.cleanEventTitle
import de.norm.events.scraper.extractEventSlug
import de.norm.events.scraper.hrefAt
import de.norm.events.scraper.imgSrcAt
import de.norm.events.scraper.isNonArtistName
import de.norm.events.scraper.parseGermanDate
import de.norm.events.scraper.parseTime
import de.norm.events.scraper.textAt
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.LocalTime

/**
 * Pure HTML parser for Ritter Butzke event detail pages (`/event/DDMMYY-<Name>`).
 *
 * Each page restates the card's title and date — the date now with a **four-digit year** — and
 * adds the three things the listing cannot carry: an `ab HH:mm` start time, the ticket shop, and
 * a `Line Up:` block naming the night's DJs one per row.
 *
 * Two shape notes. The whole header block is **rendered twice**, once for each Bootstrap
 * breakpoint (`d-block d-lg-none` / `d-none d-lg-block`), so every field is read with
 * `selectFirst` rather than collected. And the ticket link comes in two forms: a pretix widget
 * whose `event` attribute holds the shop URL, or a Resident Advisor button.
 *
 * @see RitterButzkeOverviewPageScraper for the listing parser (discovery, date, poster).
 * @see RitterButzkeWebsiteImporter for the HTTP fetch orchestrator.
 * @see <a href="https://club.ritterbutzke.com/event/070826-Unisonw-ZappedRecords-NizarSarakbi-JosefinaTapia">Example detail page</a>
 */
class RitterButzkeDetailPageScraper {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses an event detail page into a [ScrapedEvent], or `null` when the page carries no title.
     *
     * @param sourceUrl the event's URL, used as [ScrapedEvent.sourceUrl] and to derive the
     *   [ScrapedEvent.sourceId].
     */
    @Suppress("ReturnCount") // A guard clause for the missing title is clearer than nesting
    fun scrape(
        document: Document,
        sourceUrl: String
    ): ScrapedEvent? {
        val rawTitle = document.textAt("h1")
        if (rawTitle == null) {
            logger.warn { "Ritter Butzke detail page at $sourceUrl has no title, skipping" }
            return null
        }
        val slug = extractEventSlug(sourceUrl, "/event/")

        return ScrapedEvent(
            title = cleanEventTitle(rawTitle),
            eventType = EventType.PARTY.name,
            // The header renders the date with a four-digit year here, unlike the listing card.
            eventDate = parseGermanDate(document.textAt(DATE_SELECTOR)) ?: UNRESOLVED_EVENT_DATE,
            startTime = parseStartTime(document.textAt(TIME_SELECTOR)),
            imageUrl = document.imgSrcAt("img.img-fluid"),
            sourceUrl = sourceUrl,
            sourceId = "${EventSource.RITTER_BUTZKE.sourceIdPrefix}$slug",
            ticketUrl = parseTicketUrl(document),
            artists = parseLineup(document)
        )
    }

    /**
     * Reads the ticket-shop URL. Most nights sell through a **pretix** widget, whose `event`
     * attribute carries the shop URL (the widget itself renders client-side, so the attribute is
     * the only server-rendered copy); the rest link out to Resident Advisor from a plain button.
     */
    private fun parseTicketUrl(document: Document): String? =
        document.attrAt("div.pretix-widget-compat[event]", "event")?.takeIf { it.startsWith("http") }
            ?: document.hrefAt("a.btn-outline-primary")

    /**
     * Parses the `"ab 22:00"` start line — the venue writes an opening time ("from"), never a
     * doors/start pair, so it is stored as the start time and `doorsTime` is left empty.
     */
    private fun parseStartTime(text: String?): LocalTime? = parseTime(START_TIME_PATTERN.find(text.orEmpty())?.groupValues?.get(1))

    /**
     * Builds the DJ roster from the `Line Up:` block — a `<p>` label followed by one indented
     * `div` per act, each wrapping the DJ's name in a link to their profile.
     *
     * The block has no class of its own, so it is reached contextually from the label and then
     * narrowed by the **indent style** the template gives only these rows. That is a
     * presentational selector, which ADR-007 would normally rule out, but it is the sole marker
     * separating a lineup row from the prose that follows it: the refund notice and the YouTube
     * embed are sibling `div`s too, and they are what a label-only scope wrongly collects. Those
     * siblings all carry classes, so the style test is the discriminator.
     *
     * The event *title* is a night/series name (`House of Rave w/ …`), never an act, so it is not
     * minted as an artist; only these rows are, each as a `DJ`.
     */
    private fun parseLineup(document: Document): List<ScrapedArtist> {
        val label = document.select("p").firstOrNull { LINEUP_LABEL.containsMatchIn(it.text()) } ?: return emptyList()
        return label
            .parent()
            ?.select(LINEUP_ROW_SELECTOR)
            .orEmpty()
            .filter { isAfter(label, it) }
            .mapNotNull { it.text().trim().takeIf { name -> name.isNotBlank() } }
            .distinct()
            .filterNot { isNonArtistName(it) }
            .map { ScrapedArtist(name = it, role = "DJ") }
    }

    /** Whether [candidate] follows the `Line Up:` [label] in document order. */
    private fun isAfter(
        label: Element,
        candidate: Element
    ): Boolean = candidate.siblingIndex() > label.siblingIndex()
}

/** The detail header's date cell, rendered with a four-digit year. */
private const val DATE_SELECTOR = "h2.text-center.mb-0"

/** The detail header's opening-time line. */
private const val TIME_SELECTOR = "h5.text-center"

/** Matches the venue's `"ab HH:mm"` opening-time spelling. */
private val START_TIME_PATTERN = Regex("""ab\s+(\d{1,2}:\d{2})""", RegexOption.IGNORE_CASE)

/**
 * A lineup row: an indented, class-less `div`. The template gives only these rows the
 * `padding-left` inline style; the refund notice and video embed that follow carry classes.
 */
private const val LINEUP_ROW_SELECTOR = "div[style*=padding-left]"

/** The label introducing the DJ roster. */
private val LINEUP_LABEL = Regex("""^\s*line\s*up\s*:""", RegexOption.IGNORE_CASE)
