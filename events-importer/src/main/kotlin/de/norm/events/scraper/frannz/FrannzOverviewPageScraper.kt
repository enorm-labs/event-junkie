package de.norm.events.scraper.frannz

import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.buildArtistsForEventType
import de.norm.events.scraper.cleanEventTitle
import de.norm.events.scraper.hrefAt
import de.norm.events.scraper.imgSrcAt
import de.norm.events.scraper.mapEventType
import de.norm.events.scraper.parseEventStatus
import de.norm.events.scraper.parsePriceValue
import de.norm.events.scraper.parseTime
import de.norm.events.scraper.refineConcertVenueType
import de.norm.events.scraper.resolveUrl
import de.norm.events.scraper.textAt
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.time.MonthDay
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.DateTimeParseException
import java.util.Locale

/**
 * Pure HTML parser for Frannz Club Berlin's WordPress homepage event listing.
 *
 * Every upcoming event renders server-side on the homepage inside `<article class="events">`
 * blocks with a semantic `event-*` class vocabulary, and a hidden `.entry-content` per article
 * carrying the poster, a structured price breakdown, an optional ticket link and the description.
 *
 * - **No year in the rendered date** — day number plus full German month name (`11` + `Juli`).
 * The year is the nearest future occurrence, as in
 * [de.norm.events.scraper.privatclub.PrivatclubOverviewPageScraper].
 * - **No structured status markers.** The *description* is deliberately not read for one:
 * "ausverkauft" turns up in ordinary prose ("ihrer restlos *ausverkauften* Tour"), a false
 * positive. The **title** is different — the venue appends the note tersely ("MAD TSAI -verlegt
 * ins Gretchen-"), so the status is read from the raw title via [parseEventStatus] before
 * [cleanEventTitle] strips that note, the way Metropol reads its `"Verlegt ins <venue> –"`
 * prefix. A show that moved *out* is stored `RELOCATED` rather than `SCHEDULED` at a venue it
 * will not play.
 *
 * The stable identity is the WordPress post id (`<article id="post-9874">`), used for the
 * `sourceId` and a `#post-<id>` deep-link `sourceUrl` back into the listing.
 *
 * @see FRANNZ_LIMITATIONS for what the venue does not publish.
 * @see FrannzWebsiteImporter for the HTTP fetch orchestrator.
 */
@Suppress("TooManyFunctions")
class FrannzOverviewPageScraper(
    /** Clock for year-rollover date inference; override in tests. */
    private val clock: Clock = Clock.systemDefaultZone()
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses all events from the Frannz homepage: each `<article class="events">` in the main
     * listing. The highlight carousel of `article.events.highlight` teaser cards (a subset without
     * full data) is excluded via `:not(.highlight)`.
     *
     * @param baseUrl the URL the document was fetched from, for resolving relative links.
     */
    fun scrape(
        document: Document,
        baseUrl: String
    ): List<ScrapedEvent> {
        val articles = document.select("article.events:not(.highlight)")
        logger.info { "Found ${articles.size} event article(s) on Frannz homepage" }

        @Suppress("TooGenericExceptionCaught") // Intentional: skip individual malformed events without aborting the import
        return articles.mapNotNull { article ->
            try {
                parseArticle(article, baseUrl)
            } catch (e: Exception) {
                logger.warn(e) { "Failed to parse Frannz event article, skipping" }
                null
            }
        }
    }

    /**
     * Parses one `<article class="events">` into a [ScrapedEvent], or `null` when title or a
     * parseable date cannot be resolved, so malformed articles never reach persistence.
     */
    @Suppress("ReturnCount") // Guard clauses for the required title/date/id fields are clearer than nesting
    private fun parseArticle(
        article: Element,
        baseUrl: String
    ): ScrapedEvent? {
        val rawTitle = article.textAt("h2.event-title")
        if (rawTitle.isNullOrBlank()) {
            logger.warn { "Frannz event article has no title, skipping" }
            return null
        }
        // Read the status off the *raw* title first — cleanEventTitle strips the very note it comes
        // from ("… -verlegt ins Gretchen-", "… Nachholtermin vom …").
        val status = parseEventStatus(rawTitle)
        val statusNote = rawTitle
        // Strip a trailing "Nachholtermin vom …" reschedule note the venue appends to moved shows.
        val title = cleanEventTitle(rawTitle)

        val eventDate = parseEventDate(article)
        if (eventDate == null) {
            logger.warn { "Could not parse date for Frannz event '$title', skipping" }
            return null
        }

        // WordPress post id (e.g. "post-9874") is the stable per-event identity.
        val postId = article.id().removePrefix("post-").takeIf { it.isNotBlank() }
        if (postId == null) {
            logger.warn { "Frannz event '$title' has no post id, skipping" }
            return null
        }

        val subtitle = article.textAt("h4.event-utitle")
        val eventType = refineConcertVenueType(parseEventType(article), title)

        val doorsTime = parseTime(article.textAt("ul.event-times li.event-entrance .value"))
        val startTime = parseTime(article.textAt("ul.event-times li.event-start .value"))

        val (pricePresale, priceBoxOffice) = parsePrices(article)
        val lines = descriptionLines(article)

        return ScrapedEvent(
            title = title,
            subtitle = subtitle,
            description = parseDescription(lines),
            eventType = eventType,
            eventDate = eventDate,
            doorsTime = doorsTime,
            startTime = startTime,
            imageUrl = article.imgSrcAt(".sidebar-element-wrap.image img"),
            // No per-event pages; deep-link into the listing via the post-id anchor.
            sourceUrl = resolveUrl(baseUrl, "#post-$postId"),
            sourceId = "${EventSource.FRANNZ.sourceIdPrefix}$postId",
            ticketUrl = parseTicketUrl(article, lines),
            pricePresale = pricePresale,
            priceBoxOffice = priceBoxOffice,
            status = status,
            statusNote = statusNote,
            artists = buildArtistsForEventType(title, subtitle, eventType),
            promoters = parsePromoters(article.textAt("h4.event-otitle"))
        )
    }

    /**
     * The event date from `.event-day` (day number) and `.event-month` (full German month name).
     * No year is rendered, so the [MonthDay] resolves to its nearest future occurrence: this year,
     * or next when the date has passed. The listing is chronological and wraps December → January,
     * which the rollover handles.
     */
    @Suppress("ReturnCount") // Null-safe early exits per date component are clearer than nested let-chains
    private fun parseEventDate(article: Element): LocalDate? {
        val day = article.textAt(".event-day") ?: return null
        val month = article.textAt(".event-month") ?: return null

        val monthDay =
            try {
                MonthDay.parse("$day. $month", GERMAN_DATE_FORMATTER)
            } catch (_: DateTimeParseException) {
                return null
            }

        val now = LocalDate.now(clock)
        val candidate = monthDay.atYear(now.year)
        return if (candidate.isBefore(now)) candidate.plusYears(1) else candidate
    }

    /**
     * The event type from the article's `event_typ-<token>` taxonomy class — a controlled
     * WordPress taxonomy, more stable than the `.event-typ` label text. `event_typ-highlight` is a
     * carousel flag, not a type, and ignored. Frannz tokens (`ballroomparty`, `kinotv`) are
     * synonyms; `konzert` / `party` resolve via the base table. Unrecognized or absent yields
     * `null`; the caller falls back to title inference ([refineConcertVenueType]).
     */
    private fun parseEventType(article: Element): String? {
        val token =
            article
                .classNames()
                .firstOrNull { it.startsWith(EVENT_TYP_PREFIX) && it != HIGHLIGHT_CLASS }
                ?.removePrefix(EVENT_TYP_PREFIX)
        return mapEventType(token, FRANNZ_TYPE_SYNONYMS)
    }

    /**
     * Presale and box-office prices from the structured `li.event-vvk` items. Each pairs a
     * `.value` ("10,00 €") with a `.key` label; a label containing "Abendkasse" is box office,
     * everything else (Vorverkauf / "VVK …") presale. First value per category wins.
     *
     * **Most articles carry no such item, and that is the source rather than this parser.** Over a
     * 30-day window, 5 of 29 nights state a figure and all 5 are read; the other 24 name the ticket
     * seller instead. [FRANNZ_LIMITATIONS] declares it (#1781).
     */
    private fun parsePrices(article: Element): Pair<BigDecimal?, BigDecimal?> {
        var presale: BigDecimal? = null
        var boxOffice: BigDecimal? = null

        for (item in article.select("ul.event-info li.event-vvk")) {
            val value = parsePriceValue(item.textAt(".value")) ?: continue
            val label = item.textAt(".key")?.lowercase().orEmpty()
            if (label.contains("abendkasse")) {
                boxOffice = boxOffice ?: value
            } else {
                presale = presale ?: value
            }
        }

        return presale to boxOffice
    }

    /**
     * The ticket link: the venue's own shop first. A show sold through Eventim has no such anchor
     * and names the seller in its "Tickets im VVK gibt es bei …" line (#1496) — the event's own
     * Eventim page when the CMS linked it, else the seller's front page when the line only spells
     * the host. Less than a per-event link, but the venue's own pointer rather than nothing.
     */
    private fun parseTicketUrl(
        article: Element,
        lines: List<String>
    ): String? =
        article.hrefAt(".entry-content-wrap a[href*=\"shop.copilot.events\"]")
            ?: article.hrefAt(".entry-content-wrap a[href*=\"eventim\"]")
            ?: lines.firstOrNull { TICKET_PROMO_LINE.containsMatchIn(it) && it.contains(EVENTIM_HOST, ignoreCase = true) }?.let { EVENTIM_URL }

    /**
     * The cleaned `<br>`-delimited lines of the hidden `.entry-content` body. The element mixes the
     * `.sidebar` (image + info/price facts) with the blurb as `<br>`-separated sibling text nodes;
     * the sidebar subtree is skipped, each line [cleaned][cleanDescriptionLine], blank or
     * bullet-only residue dropped.
     */
    private fun descriptionLines(article: Element): List<String> {
        val content = article.selectFirst(".entry-content-wrap > .content") ?: return emptyList()

        val lines = mutableListOf<String>()
        val current = StringBuilder()
        for (node in content.childNodes()) {
            when {
                // facts live in the sidebar, not the blurb
                node is Element && node.hasClass("sidebar") -> {
                    continue
                }

                node is Element && node.tagName().equals("br", ignoreCase = true) -> {
                    lines.add(current.toString())
                    current.clear()
                }

                node is Element -> {
                    current.append(node.text())
                }

                node is TextNode -> {
                    current.append(node.text())
                }
            }
        }
        lines.add(current.toString())

        return lines
            .map { cleanDescriptionLine(it) }
            .filter { it.isNotBlank() && !BULLET_ONLY.matches(it) }
    }

    /**
     * The description: the [lines][descriptionLines] minus the "Tickets im VVK gibt es bei …"
     * promo line — a markdown-link artifact rendered raw, read for the ticket link by
     * [parseTicketUrl] before it goes. `null` when no prose remains.
     */
    private fun parseDescription(lines: List<String>): String? =
        lines
            .filterNot { TICKET_PROMO_LINE.containsMatchIn(it) }
            .joinToString("\n")
            .takeIf { it.isNotBlank() }

    /**
     * Cleans one `<br>`-delimited line of the raw Markdown the copilot.events CMS emits. Frannz
     * renders it literally, so a line arrives as `-Tickets im VVK gibt es bei
     * [www.eventim.de](www.eventim.de) -`. Strips a leading/trailing list bullet and unwraps inline
     * `[label](url)` links to their label (URL dropped), so `[www.eventim.de](www.eventim.de)`
     * becomes `www.eventim.de`. Callers still drop the "Tickets im VVK …" line and bullet residue.
     */
    private fun cleanDescriptionLine(raw: String): String =
        raw
            .replaceFirst(LEADING_BULLET, "")
            .replace(TRAILING_BULLET, "")
            .replace(MARKDOWN_LINK) { it.groupValues[1] }
            .trim()

    /**
     * Promoter names from an `.event-otitle` presenter line: "<names> präsentiert:" / "<names>
     * präsentieren:" ("Loft & Flux FM präsentieren:"), split on comma / `&` / `/` / "und". An
     * over-title without a presenter marker ("Live im Frannz Biergarten:") is not a promoter and
     * yields an empty list.
     */
    private fun parsePromoters(otitle: String?): List<String> {
        val names = otitle?.let { PRESENTER_PATTERN.find(it)?.groupValues?.get(1) } ?: return emptyList()
        return names
            .split(PROMOTER_SEPARATOR)
            .map { it.trim() }
            .filter { it.isNotBlank() }
    }

    companion object {
        private const val EVENT_TYP_PREFIX = "event_typ-"
        private const val HIGHLIGHT_CLASS = "event_typ-highlight"

        /**
         * A ticket-shop promo line, read for the seller and then dropped. The connector varies ("… gibt
         * es bei / unter / hier: <shop>"), so the match keys only on the stable opening — "Tickets"
         * immediately followed by "im VVK" or "gibt es". That keeps a genuine sentence mentioning
         * tickets ("Tickets **für den** … behalten ihre Gültigkeit, …"), whose second word is neither.
         */
        private val TICKET_PROMO_LINE =
            Regex("""^Tickets\s+(?:im\s+VVK|gibt\s+es)\b""", RegexOption.IGNORE_CASE)

        /** The seller a promo line names when the venue does not sell the show itself. */
        private const val EVENTIM_HOST = "eventim.de"

        /** Where a promo line that only spells [EVENTIM_HOST] points: the seller's front page. */
        private const val EVENTIM_URL = "https://www.eventim.de/"

        /** A leading Markdown list-item bullet ("- ", "* ", "• ") to strip from a description line. */
        private val LEADING_BULLET = Regex("""^\s*[-–—*•]\s*""")

        /** A trailing detached bullet/dash (requires leading space, so hyphenated words are safe). */
        private val TRAILING_BULLET = Regex("""\s+[-–—*•]\s*$""")

        /** A description line that is only bullet/dash residue after cleaning. */
        private val BULLET_ONLY = Regex("""^[-–—*•\s]*$""")

        /** Inline Markdown link `[label](url)` — kept as its visible label, the URL dropped. */
        private val MARKDOWN_LINK = Regex("""\[([^\]]+)]\(([^)]*)\)""")

        /** Frannz-specific `event_typ-*` tokens not covered by the shared base synonym table. */
        private val FRANNZ_TYPE_SYNONYMS: Map<String, String> =
            mapOf(
                "ballroomparty" to EventType.PARTY.name,
                "kinotv" to EventType.OTHER.name
            )

        /**
         * "day. FullGermanMonth" ("11. Juli" → July 11), case-insensitive [Locale.GERMAN] month names.
         */
        private val GERMAN_DATE_FORMATTER: DateTimeFormatter =
            DateTimeFormatterBuilder()
                .parseCaseInsensitive()
                .appendPattern("d. MMMM")
                .toFormatter(Locale.GERMAN)

        /** Captures the promoter names preceding a "präsentiert:" / "präsentieren:" marker. */
        private val PRESENTER_PATTERN =
            Regex("""(.+?)\s+präsentier(?:t|en)\s*:?\s*$""", RegexOption.IGNORE_CASE)

        /** Splits a multi-promoter presenter string on comma / `&` / `/` / "und". */
        private val PROMOTER_SEPARATOR = Regex("""\s*(?:,|&|/|\bund\b)\s*""", RegexOption.IGNORE_CASE)
    }
}
