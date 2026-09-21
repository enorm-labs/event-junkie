package de.norm.events.scraper.lido

import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ISO_DATE_LENGTH
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.UNRESOLVED_EVENT_DATE
import de.norm.events.scraper.buildArtistsForEventType
import de.norm.events.scraper.extractEventSlug
import de.norm.events.scraper.mapEventType
import de.norm.events.scraper.parseEventStatus
import de.norm.events.scraper.parseIsoDate
import de.norm.events.scraper.parseRealDate
import de.norm.events.scraper.parseTime
import de.norm.events.scraper.refineConcertVenueType
import de.norm.events.scraper.resolveUrl
import de.norm.events.scraper.supportSubtitleLine
import de.norm.events.scraper.textAt
import de.norm.events.scraper.textLinesAt
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.LocalDate
import java.time.LocalTime

/**
 * Pure HTML parser for Lido Berlin's event listing (overview) page.
 *
 * Lido runs on the same "Kulturhäuser" platform as Astra Kulturhaus, with a different theme:
 * `article.event-ticket` / `event-ticket__*` blocks rather than Astra's `article.event` /
 * `event__*`. The platform-shared parts (`data-realdate`, `/events/<date-slug>` URLs, the
 * German status labels, and on detail pages `.price`/`.gig__description`/
 * `.purchase-option__button`) use the shared scraper helpers; the theme-specific selectors live
 * in [parseLidoEventBlock].
 *
 * Upcoming events are on the homepage (`/`) as `article.event-ticket` blocks — `/events` is the
 * (broken-dated) past archive, so the event source points at the homepage. The
 * `teaser__next-events` block above the list is read too, for the day's own event the list can
 * leave out ([parseTeaser], #1530).
 *
 * The overview is the source for event type, sold-out flag, status, date, and the artist roster
 * (which needs both subtitle and type). The detail page (the merge's primary side) adds
 * description, prices, ticket URL and image. [LidoWebsiteImporter] merges.
 *
 * @see LidoDetailPageScraper for the detail-page data source.
 * @see LidoWebsiteImporter for the HTTP fetch orchestrator.
 * @see <a href="https://www.lido-berlin.de/">Lido Berlin</a>
 */
class LidoOverviewPageScraper {
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
        val articles = document.select("article.event-ticket")
        logger.info { "Found ${articles.size} event article(s) on overview page" }

        @Suppress("TooGenericExceptionCaught") // Intentional: skip individual malformed events without aborting the entire import
        val listed =
            articles.mapNotNull { article ->
                try {
                    parseArticle(article, baseUrl)
                } catch (e: Exception) {
                    logger.warn(e) { "Failed to parse event article, skipping" }
                    null
                }
            }
        val listedUrls = listed.mapTo(mutableSetOf()) { it.sourceUrl }
        return listed + parseTeaser(document, baseUrl).filterNot { it.sourceUrl in listedUrls }
    }

    /**
     * The `teaser__next-events` block at the top of the home page as a second source of events
     * (#1530). On a Lido night it read `Today / 19:00 / VTOROI KA` while the article list began
     * two days later, so the night's own event was on the page and not in the block this scraper
     * reads. A teaser entry the list also carries is dropped by the caller; one it does not carry
     * becomes an event with the date from its `/events/<yyyy-MM-dd-…>` slug, the teaser's time as
     * the doors (the venue prints doors there — 19:00 for a 20:00 start) and the board lines as
     * the title. The detail page fills in the rest.
     */
    private fun parseTeaser(
        document: Document,
        baseUrl: String
    ): List<ScrapedEvent> =
        document.select(".teaser__next-events__wrapper__event").mapNotNull { entry ->
            val link = entry.selectFirst(".teaser__next-events__wrapper__event__title a[href]") ?: return@mapNotNull null
            val sourceUrl = resolveUrl(baseUrl, link.attr("href"))
            val slug = extractEventSlug(sourceUrl)
            val eventDate = parseIsoDate(slug.take(ISO_DATE_LENGTH)) ?: return@mapNotNull null
            val title =
                link
                    .select(".boad-line")
                    .joinToString(" ") { it.text().trim() }
                    .trim()
                    .ifBlank { link.text().trim() }
            if (title.isBlank()) return@mapNotNull null
            val eventType = refineConcertVenueType(null, title)
            ScrapedEvent(
                title = title,
                eventType = eventType,
                eventDate = eventDate,
                doorsTime = parseTime(entry.textAt(".teaser__next-events__wrapper__event__date__time")),
                sourceUrl = sourceUrl,
                sourceId = "${EventSource.LIDO.sourceIdPrefix}$slug",
                artists = buildArtistsForEventType(title, null, eventType)
            )
        }

    /** Parses one `article.event-ticket` block into a [ScrapedEvent]. */
    private fun parseArticle(
        article: Element,
        baseUrl: String
    ): ScrapedEvent? {
        val block = parseLidoEventBlock(article, baseUrl) ?: return null

        return ScrapedEvent(
            title = block.title,
            subtitle = block.subtitle,
            eventType = block.eventType,
            // Overview articles always carry `data-realdate`; the sentinel is purely defensive.
            eventDate = block.eventDate ?: UNRESOLVED_EVENT_DATE,
            doorsTime = block.doorsTime,
            startTime = block.startTime,
            sourceUrl = block.sourceUrl,
            sourceId = "${EventSource.LIDO.sourceIdPrefix}${extractEventSlug(block.sourceUrl)}",
            soldOut = block.soldOut,
            status = block.status,
            promoters = block.promoters,
            promoterWebsites = block.promoterWebsites,
            // Extract support only from the subtitle line carrying the "Support:" marker, so a note on a
            // later <br> line (e.g. a cancellation notice) is not taken for a support act.
            artists =
                buildArtistsForEventType(
                    block.title,
                    supportSubtitleLine(article.textLinesAt(".event-ticket__content__subtitle")),
                    block.eventType
                )
        )
    }
}

/**
 * Common fields from the `event-ticket__*` markup shared by overview articles
 * (`article.event-ticket`) and the detail-page header (`header.event-ticket`).
 */
internal data class LidoEventBlock(
    val title: String,
    val sourceUrl: String,
    /** `null` when the root carries no `data-realdate` (e.g. the detail header). */
    val eventDate: LocalDate?,
    val doorsTime: LocalTime?,
    val startTime: LocalTime?,
    /** Mapped event type, or `null` without a type label. */
    val eventType: String?,
    val subtitle: String?,
    val soldOut: Boolean,
    val status: String,
    val promoters: List<String>,
    /** The presenter anchor's `href`, keyed by the presenter's name, where the venue links one (#1319). */
    val promoterWebsites: Map<String, String>
)

/**
 * Parses the shared `event-ticket__*` markup into a [LidoEventBlock]. [root] scopes one event —
 * `article.event-ticket` on the overview, `header.event-ticket` on a detail page. `null`
 * without a title link.
 */
@Suppress("ReturnCount") // Guard clauses for the required title/href are clearer than nesting
internal fun parseLidoEventBlock(
    root: Element,
    baseUrl: String
): LidoEventBlock? {
    val titleLink = root.selectFirst(".event-ticket__content__title a") ?: return null
    val title = titleLink.text().trim().takeIf { it.isNotBlank() } ?: return null
    val href = titleLink.attr("href").takeIf { it.isNotBlank() } ?: return null
    val sourceUrl = resolveUrl(baseUrl, href)

    val statusText = root.textAt(".event-ticket__content__status__label")?.lowercase().orEmpty()
    val (doorsTime, startTime) = parseLidoTimes(root)

    return LidoEventBlock(
        title = title,
        sourceUrl = sourceUrl,
        eventDate = parseRealDate(root.attr("data-realdate")),
        doorsTime = doorsTime,
        startTime = startTime,
        eventType = refineConcertVenueType(mapEventType(root.textAt(".event-ticket__type__label")), title),
        subtitle = root.textAt(".event-ticket__content__subtitle"),
        soldOut = statusText.contains("ausverkauft") || statusText.contains("sold out"),
        status = parseEventStatus(statusText),
        promoters = parseLidoPresenters(root),
        promoterWebsites = parseLidoPresenterWebsites(root)
    )
}

/**
 * Doors (Einlass) and start (Start/Beginn) times. The meta block renders twice (desktop +
 * mobile), each with both times, so the first value per label wins.
 */
private fun parseLidoTimes(root: Element): Pair<LocalTime?, LocalTime?> {
    var doors: LocalTime? = null
    var start: LocalTime? = null
    for (slot in root.select(".event-ticket__meta__times__time")) {
        val label =
            slot
                .selectFirst(".event-ticket__meta__times__time__label")
                ?.text()
                ?.lowercase()
                .orEmpty()
        val value = parseTime(slot.textAt(".event-ticket__meta__times__time__value"))
        when {
            label.contains("einlass") -> doors = doors ?: value
            label.contains("start") || label.contains("beginn") -> start = start ?: value
        }
    }
    return doors to start
}

/**
 * The presenter(s) from the `event-ticket__meta__presenter` block: the anchor text
 * ("Puschen"), or without a link the block's text minus the trailing "präsentiert". Empty
 * when none is shown.
 */
private fun parseLidoPresenters(root: Element): List<String> {
    val presenter = root.selectFirst(".event-ticket__meta__presenter") ?: return emptyList()
    val name =
        presenter.selectFirst("a")?.text()?.trim()
            ?: presenter.text().substringBefore("präsentiert").trim()
    return listOfNotNull(name.takeIf { it.isNotBlank() })
}

/** The presenter's link, where the name is an anchor. */
private fun parseLidoPresenterWebsites(root: Element): Map<String, String> =
    root
        .select(".event-ticket__meta__presenter a")
        .mapNotNull { link ->
            val name = link.text().trim().takeIf { it.isNotBlank() }
            val url = link.absUrl("href").takeIf { it.startsWith("http") }
            if (name != null && url != null) name to url else null
        }.toMap()
