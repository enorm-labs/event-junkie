package de.norm.events.scraper.astra

import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.UNRESOLVED_EVENT_DATE
import de.norm.events.scraper.buildArtistsForEventType
import de.norm.events.scraper.extractEventSlug
import de.norm.events.scraper.imgSrcAt
import de.norm.events.scraper.mapEventType
import de.norm.events.scraper.parseEventStatus
import de.norm.events.scraper.parseGermanShortDate
import de.norm.events.scraper.parseRealDate
import de.norm.events.scraper.parseTime
import de.norm.events.scraper.refineConcertVenueType
import de.norm.events.scraper.resolveUrl
import de.norm.events.scraper.supportSubtitleLine
import de.norm.events.scraper.textAt
import de.norm.events.scraper.textLines
import de.norm.events.scraper.textLinesAt
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.LocalDate
import java.time.LocalTime

/**
 * Pure HTML parser for Astra Kulturhaus' event listing (overview) page.
 *
 * Astra runs on the shared "Kulturhäuser" platform (same as Lido). Upcoming
 * events are listed on the homepage (`/`) as a series of `article.event`
 * blocks — the `/events` path is the past-events archive, not the program.
 *
 * The overview page serves two purposes:
 * 1. **Discovery** — identifies all event detail URLs for enrichment.
 * 2. **Authoritative source for the event type** — the `kind` label ("Concert",
 *    "Festival", …) can appear on both pages, but only the overview applies the
 *    festival-day normalization (see [normalizeFestivalDays]), so its type wins
 *    in the merge. The detail page is the primary source for everything else
 *    (promoter, prices, ticket URL, description). The `sold out` badge may render
 *    on either page; the merge ORs the flag, so it is captured wherever it
 *    appears. Merging is handled by [AstraWebsiteImporter].
 *
 * Most fields are parsed from the `.event__*` markup shared with the detail
 * page (see [parseAstraEventBlock]); artist extraction is done here because it
 * needs both the subtitle (support acts) and the `kind`-derived event type,
 * which only coincide on the overview page.
 *
 * @see AstraDetailPageScraper for the primary per-event data source.
 * @see AstraWebsiteImporter for the HTTP fetch orchestrator.
 * @see <a href="https://www.astra-berlin.de/">Astra Kulturhaus</a>
 */
class AstraOverviewPageScraper {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses all event articles from the overview page document.
     *
     * @param baseUrl the URL the document was fetched from, used for resolving
     *   relative detail links and building `sourceId` values.
     */
    fun scrape(
        document: Document,
        baseUrl: String
    ): List<ScrapedEvent> {
        val articles = document.select("article.event")
        logger.info { "Found ${articles.size} event article(s) on overview page" }

        @Suppress("TooGenericExceptionCaught") // Intentional: skip individual malformed events without aborting the entire import
        val events =
            articles.mapNotNull { article ->
                try {
                    parseArticle(article, baseUrl)
                } catch (e: Exception) {
                    logger.warn(e) { "Failed to parse event article, skipping" }
                    null
                }
            }
        return normalizeFestivalDays(events)
    }

    /**
     * Repairs Astra's occasional per-day mislabeling of multi-day festivals.
     *
     * Astra lists each festival day as its own article sharing one title (e.g.
     * "OUT OF LINE WEEKENDER 2027" / "Day 1…3"), but the `kind` label is entered
     * per day and sometimes wrong — one day can read "Concert" while its siblings
     * read "Festival". When at least one event with a given title is a confident
     * [FESTIVAL][EventType.FESTIVAL], the siblings tagged otherwise are corrected
     * to `FESTIVAL`, and the title-as-headliner artist that [buildArtistsForEventType] added
     * for the bogus `CONCERT` is dropped (real festival days carry no artists).
     *
     * Only acts when a correctly-labeled festival sibling exists on the same page,
     * so a standalone concert is never reclassified.
     */
    private fun normalizeFestivalDays(events: List<ScrapedEvent>): List<ScrapedEvent> {
        val festivalTitles =
            events
                .filter { it.eventType == EventType.FESTIVAL.name }
                .map { it.title }
                .toSet()

        return events.map { event ->
            if (event.eventType != EventType.FESTIVAL.name && event.title in festivalTitles) {
                event.copy(eventType = EventType.FESTIVAL.name, artists = emptyList())
            } else {
                event
            }
        }
    }

    /**
     * Parses a single `article.event` block into a [ScrapedEvent].
     *
     * The featured "teaser" article at the top of the page has no date in its
     * markup; such events fall back to the [UNRESOLVED_EVENT_DATE] sentinel so they
     * are still discovered. The detail page (the primary source) then supplies the
     * real date via [AstraWebsiteImporter.fillGapsFromOverview].
     */
    private fun parseArticle(
        article: Element,
        baseUrl: String
    ): ScrapedEvent? {
        val block = parseAstraEventBlock(article, baseUrl) ?: return null

        // Astra omits the `kind` label for some events and dumps others in its
        // generic "Other" kind; refine both from the title here (not in the shared
        // block parser) so the authoritative overview type is set while the detail
        // scraper still leaves the type to the overview. See refineConcertVenueType.
        val eventType = refineConcertVenueType(block.eventType, block.title)

        return ScrapedEvent(
            title = block.title,
            subtitle = block.subtitle,
            // The overview has no prose of its own; a banner is the only thing to store.
            description = block.notice,
            eventType = eventType,
            // Sentinel for the dateless teaser; the detail page fills the real date in.
            eventDate = block.eventDate ?: UNRESOLVED_EVENT_DATE,
            doorsTime = block.doorsTime,
            startTime = block.startTime,
            imageUrl = block.imageUrl,
            sourceUrl = block.sourceUrl,
            sourceId = "${EventSource.ASTRA.sourceIdPrefix}${extractEventSlug(block.sourceUrl)}",
            soldOut = block.soldOut,
            status = block.status,
            // Isolate the subtitle's "Support:" line so a note appended on a later
            // <br> line (e.g. a cancellation notice) can't be mistaken for a support act.
            artists =
                buildArtistsForEventType(
                    block.title,
                    supportSubtitleLine(article.textLinesAt(".event__subtitle")),
                    eventType
                )
        )
    }
}

/**
 * Common fields parsed from the shared `.event__*` markup that both the
 * overview articles and the detail page header use.
 */
internal data class AstraEventBlock(
    val title: String,
    val sourceUrl: String,
    /** `null` for the dateless featured teaser on the overview page. */
    val eventDate: LocalDate?,
    val doorsTime: LocalTime?,
    val startTime: LocalTime?,
    /** Mapped event type, or `null` when no `kind` label is present. */
    val eventType: String?,
    val subtitle: String?,
    /** A shouted notice the venue appends below the subtitle — a relocation, a sold-out warning — or `null`. */
    val notice: String?,
    val imageUrl: String?,
    val soldOut: Boolean,
    val status: String
)

/**
 * Parses the `.event__*` markup shared by overview articles and the detail
 * page header into an [AstraEventBlock].
 *
 * [root] is the element scoping a single event — an `article.event` on the
 * overview page, or the `main.page-content` container on a detail page (which
 * holds exactly one event). Returns `null` when no title link is present.
 */
@Suppress("ReturnCount") // Guard clause for the required title is clearer than nesting
internal fun parseAstraEventBlock(
    root: Element,
    baseUrl: String
): AstraEventBlock? {
    val titleLink = root.selectFirst(".event__title .event__title-link") ?: return null
    val title = titleLink.text().trim().takeIf { it.isNotBlank() } ?: return null
    val href = titleLink.attr("href").takeIf { it.isNotBlank() } ?: return null
    val sourceUrl = resolveUrl(baseUrl, href)

    val statusText = root.textAt(".event__status")?.lowercase().orEmpty()
    val (subtitle, notice) = splitSubtitleNotice(root.selectFirst(".event__subtitle")?.textLines(keepBlankLines = true).orEmpty())

    return AstraEventBlock(
        title = title,
        sourceUrl = sourceUrl,
        // Prefer the machine-readable `data-realdate` (full 4-digit year, no pivot
        // ambiguity); fall back to the human `DD.MM.YY` text where it is absent
        // (e.g. on detail pages, which carry no `data-realdate`).
        eventDate = parseRealDate(root.attr("data-realdate")) ?: parseAstraDate(root.textAt(".event__date--full")),
        doorsTime = parseTime(root.textAt(".event__time--doors .event__time-value")),
        startTime = parseTime(root.textAt(".event__time--start .event__time-value")),
        eventType = mapEventType(root.textAt(".event__kind .event__label")),
        subtitle = subtitle,
        notice = notice,
        imageUrl = root.imgSrcAt(".event__right-col img.image__src"),
        soldOut = statusText.contains("sold out") || statusText.contains("ausverkauft"),
        status = parseEventStatus(statusText)
    )
}

/**
 * Separates the subtitle proper from the notice the venue appends below it.
 *
 * The `.event__subtitle` block is the tour name and a `+ Support:` line, and then, after a blank
 * `<br><br>` line, a shouted banner — `VERLEGT INS LIDO. BEREITS GEKAUFTE TICKETS BEHALTEN IHRE
 * GÜLTIGKEIT!`, `MATINEE SHOW!`. Joined as one text the banner ran into the subtitle with no
 * separator, and the support act read as "Gym Tonic Verlegt Ins Lido" (#1138). A line counts as a
 * notice when it follows a blank line **and** is shouted; a festival's lower-case lineup after the
 * same blank stays part of the subtitle. Returns the subtitle and the notice, each `null` when
 * empty.
 */
internal fun splitSubtitleNotice(lines: List<String>): Pair<String?, String?> {
    val firstBlank = lines.indexOfFirst { it.isBlank() }.takeIf { it >= 0 } ?: lines.size
    val (notice, subtitle) = lines.filter { it.isNotBlank() }.partition { line -> lines.indexOf(line) > firstBlank && line.isShouted() }
    return subtitle.joinToString(" ").ifBlank { null } to notice.joinToString(" ").ifBlank { null }
}

/** True for a line written in capitals only — the venue's style for a notice, never for a tour name or an act. */
private fun String.isShouted(): Boolean = any { it.isLetter() } && none { it.isLowerCase() }

/**
 * Parses Astra's `DD.MM.YY` date format (e.g. "11.12.26") via the shared
 * [parseGermanShortDate][de.norm.events.scraper.parseGermanShortDate]. Two-digit
 * years resolve to 2000–2099. Returns `null` for missing or unparseable input.
 *
 * Used as the fallback when no `data-realdate` attribute is present (see
 * [parseRealDate][de.norm.events.scraper.parseRealDate]).
 */
internal fun parseAstraDate(text: String?): LocalDate? = parseGermanShortDate(text)
