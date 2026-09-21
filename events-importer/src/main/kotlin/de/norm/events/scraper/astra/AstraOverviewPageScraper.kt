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
 * Astra runs on the shared "Kulturhäuser" platform (same as Lido). Upcoming events are
 * `article.event` blocks on the homepage (`/`) — `/events` is the past-events archive.
 *
 * The overview is the discovery list and the **authoritative source for the event type**: the
 * `kind` label ("Concert", "Festival", …) appears on both pages, but only the overview applies
 * the festival-day normalization ([normalizeFestivalDays]), so its type wins in the merge. The
 * detail page is primary for everything else (promoter, prices, ticket URL, description). The
 * `sold out` badge may render on either page; the merge ORs the flag. Merging is
 * [AstraWebsiteImporter]'s job.
 *
 * Most fields come from the `.event__*` markup shared with the detail page
 * ([parseAstraEventBlock]); artists are extracted here because that needs both the subtitle
 * (support acts) and the `kind`-derived type, which only coincide on the overview.
 *
 * @see AstraDetailPageScraper for the primary per-event data source.
 * @see AstraWebsiteImporter for the HTTP fetch orchestrator.
 * @see <a href="https://www.astra-berlin.de/">Astra Kulturhaus</a>
 */
class AstraOverviewPageScraper {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses all event articles from the overview page.
     *
     * @param baseUrl the URL the document was fetched from, for relative detail links and `sourceId` values.
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
     * Each festival day is its own article sharing one title ("OUT OF LINE WEEKENDER 2027" /
     * "Day 1…3"), but `kind` is entered per day and sometimes wrong — one day "Concert", its
     * siblings "Festival". When at least one event with a title is a confident
     * [FESTIVAL][EventType.FESTIVAL], the siblings tagged otherwise become `FESTIVAL`, and the
     * title-as-headliner artist [buildArtistsForEventType] added for the bogus `CONCERT` is dropped
     * (real festival days carry no artists). Only with a correctly-labeled sibling on the same
     * page, so a standalone concert is never reclassified.
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
     * Parses one `article.event` block into a [ScrapedEvent].
     *
     * The featured "teaser" article at the top has no date in its markup; it gets the
     * [UNRESOLVED_EVENT_DATE] sentinel so it is still discovered, and the detail page supplies the
     * real date via [AstraWebsiteImporter.fillGapsFromOverview].
     */
    private fun parseArticle(
        article: Element,
        baseUrl: String
    ): ScrapedEvent? {
        val block = parseAstraEventBlock(article, baseUrl) ?: return null

        // Astra omits `kind` for some events and dumps others in its generic "Other"; refine both from
        // the title here (not in the shared block parser) so the authoritative overview type is set
        // while the detail scraper leaves the type to the overview. See refineConcertVenueType.
        val eventType = refineConcertVenueType(block.eventType, block.title)

        return ScrapedEvent(
            title = block.title,
            subtitle = block.subtitle,
            // The overview has no prose; a banner is the only thing to store.
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
            // Isolate the subtitle's "Support:" line so a note on a later <br> line (e.g. a cancellation
            // notice) is not taken for a support act.
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
 * Common fields from the `.event__*` markup shared by overview articles and the detail header.
 */
internal data class AstraEventBlock(
    val title: String,
    val sourceUrl: String,
    /** `null` for the dateless featured teaser on the overview page. */
    val eventDate: LocalDate?,
    val doorsTime: LocalTime?,
    val startTime: LocalTime?,
    /** Mapped event type, or `null` without a `kind` label. */
    val eventType: String?,
    val subtitle: String?,
    /** A shouted notice below the subtitle — a relocation, a sold-out warning — or `null`. */
    val notice: String?,
    val imageUrl: String?,
    val soldOut: Boolean,
    val status: String
)

/**
 * Parses the shared `.event__*` markup into an [AstraEventBlock]. [root] scopes one event — an
 * `article.event` on the overview, `main.page-content` on a detail page (exactly one event).
 * `null` without a title link.
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
        // Prefer the machine-readable `data-realdate` (four-digit year, no pivot ambiguity); fall back
        // to the human `DD.MM.YY` where absent (detail pages carry no `data-realdate`).
        eventDate = parseRealDate(root.attr("data-realdate")) ?: parseGermanShortDate(root.textAt(".event__date--full")),
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
 * `.event__subtitle` is the tour name and a `+ Support:` line, then after a blank `<br><br>` a
 * shouted banner — `VERLEGT INS LIDO. BEREITS GEKAUFTE TICKETS BEHALTEN IHRE GÜLTIGKEIT!`,
 * `MATINEE SHOW!`. Joined as one text the banner ran into the subtitle and the support act read
 * as "Gym Tonic Verlegt Ins Lido" (#1138). A line is a notice when it follows a blank line
 * **and** is shouted; a festival's lower-case lineup after the same blank stays subtitle.
 * Returns subtitle and notice, each `null` when empty.
 */
internal fun splitSubtitleNotice(lines: List<String>): Pair<String?, String?> {
    val firstBlank = lines.indexOfFirst { it.isBlank() }.takeIf { it >= 0 } ?: lines.size
    val (notice, subtitle) = lines.filter { it.isNotBlank() }.partition { line -> lines.indexOf(line) > firstBlank && line.isShouted() }
    return subtitle.joinToString(" ").ifBlank { null } to notice.joinToString(" ").ifBlank { null }
}

/** True for a capitals-only line — the venue's style for a notice, never a tour name or act. */
private fun String.isShouted(): Boolean = any { it.isLetter() } && none { it.isLowerCase() }
