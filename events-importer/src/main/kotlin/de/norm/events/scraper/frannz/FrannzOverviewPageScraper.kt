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
 * Frannz renders every upcoming event server-side on the homepage (`/`) inside
 * `<article class="events">` blocks with a rich, semantic `event-*` class
 * vocabulary (`.event-title`, `.event-day` / `.event-month`, `.event-entrance` /
 * `.event-start`, `.event-otitle` / `.event-utitle`, `li.event-vvk`). A hidden
 * `.entry-content` per article carries the poster image, a structured price
 * breakdown, an optional ticket-shop link, and the description. The page has **no
 * per-event detail pages** (nothing links to `/events/<slug>/`), so this is a
 * single-page scrape.
 *
 * Two Frannz-specific quirks drive the design:
 * - **No year in the rendered date** — only day number + full German month name
 *   (`11` + `Juli`). The year is inferred as the nearest future occurrence,
 *   mirroring [de.norm.events.scraper.privatclub.PrivatclubOverviewPageScraper].
 * - **No structured status markers** — no badge or CSS class states a status. The
 *   *description* is not read for one: words like "ausverkauft" / "verlegt" turn up
 *   in ordinary prose there (e.g. "ihrer restlos *ausverkauften* Tour"), so deriving
 *   a status from it would produce false positives, and `soldOut` is consequently
 *   never set. The **title** is different — the venue appends the note deliberately
 *   and tersely ("MAD TSAI -verlegt ins Gretchen-"), so the status is read from the
 *   raw title via [parseEventStatus] before [cleanEventTitle] strips that same note.
 *   This mirrors Metropol, which reads its `"Verlegt ins <venue> –"` title prefix the
 *   same way. A show that moved *out* of the house is therefore stored `RELOCATED`
 *   rather than silently `SCHEDULED` at a venue it will not play.
 *
 * The stable per-event identity is the WordPress post id (`<article id="post-9874">`),
 * used for both the `sourceId` and a `#post-<id>` deep-link `sourceUrl` back into
 * the listing.
 *
 * @see FrannzWebsiteImporter for the HTTP fetch orchestrator.
 * @see <a href="https://frannz.eu/">Frannz Club Berlin</a>
 */
@Suppress("TooManyFunctions")
class FrannzOverviewPageScraper(
    /** Clock used for year-rollover date inference. Defaults to the system clock; override in tests for determinism. */
    private val clock: Clock = Clock.systemDefaultZone()
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses all events from the Frannz homepage document.
     *
     * Each event is an `<article class="events">` in the main listing. The
     * homepage also renders a highlight carousel of `article.events.highlight`
     * teaser cards (a subset of the same events, without full data); those are
     * excluded via `:not(.highlight)`.
     *
     * @param baseUrl the URL the document was fetched from, used for resolving relative links.
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
     * Parses a single `<article class="events">` element into a [ScrapedEvent].
     *
     * Skips (returns `null`) when the two required fields — title and a parseable
     * date — cannot be resolved, so malformed articles never reach persistence.
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
        // Read the status off the *raw* title first — cleanEventTitle strips the very note it is
        // derived from ("… -verlegt ins Gretchen-", "… Nachholtermin vom …").
        val status = parseEventStatus(rawTitle)
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

        return ScrapedEvent(
            title = title,
            subtitle = subtitle,
            description = parseDescription(article),
            eventType = eventType,
            eventDate = eventDate,
            doorsTime = doorsTime,
            startTime = startTime,
            imageUrl = article.imgSrcAt(".sidebar-element-wrap.image img"),
            // Frannz has no per-event pages; deep-link into the listing via the post-id anchor.
            sourceUrl = resolveUrl(baseUrl, "#post-$postId"),
            sourceId = "${EventSource.FRANNZ.sourceIdPrefix}$postId",
            ticketUrl = article.hrefAt(".entry-content-wrap a[href*=\"shop.copilot.events\"]"),
            pricePresale = pricePresale,
            priceBoxOffice = priceBoxOffice,
            status = status,
            artists = buildArtistsForEventType(title, subtitle, eventType),
            promoters = parsePromoters(article.textAt("h4.event-otitle"))
        )
    }

    /**
     * Parses the event date from the `.event-day` (day number) and `.event-month`
     * (full German month name) cells.
     *
     * Frannz renders no year, so the day/month is combined into a [MonthDay] and
     * resolved to its nearest future occurrence: the current year, or the next
     * year when that date has already passed. The listing is chronological and
     * wraps December → January, which this rollover handles.
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
     * Maps the event type from the article's `event_typ-<token>` taxonomy class.
     *
     * The `event_typ-*` classes are a controlled WordPress taxonomy — more stable
     * than the human `.event-typ` label text. `event_typ-highlight` is a carousel
     * flag, not a type, so it is ignored. Frannz-specific tokens (`ballroomparty`,
     * `kinotv`) are supplied as synonyms; `konzert` / `party` resolve via the base
     * table. An unrecognized or absent token yields `null`; the caller then falls
     * back to title-based inference ([refineConcertVenueType]).
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
     * Parses presale and box-office prices from the structured `li.event-vvk`
     * items in the info list.
     *
     * Each item pairs a `.value` (e.g. "10,00 €") with a `.key` label; a label
     * containing "Abendkasse" maps to the box-office price, everything else
     * (Vorverkauf / "VVK …") to presale. The first value per category wins.
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
     * Extracts the description from the hidden `.entry-content` body.
     *
     * The content element mixes the `.sidebar` (image + info/price facts) with the
     * free-text blurb as `<br>`-separated sibling text nodes. The sidebar subtree
     * is skipped, and the leading "Tickets im VVK gibt es bei …" line — a
     * markdown-link artifact Frannz renders raw — is dropped. Returns `null` when
     * no prose remains.
     */
    private fun parseDescription(article: Element): String? {
        val content = article.selectFirst(".entry-content-wrap > .content") ?: return null

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
            .filter { it.isNotBlank() && !BULLET_ONLY.matches(it) && !TICKET_PROMO_LINE.containsMatchIn(it) }
            .joinToString("\n")
            .takeIf { it.isNotBlank() }
    }

    /**
     * Cleans one `<br>`-delimited description line of the raw Markdown the copilot.events CMS emits.
     *
     * Frannz renders the CMS's Markdown literally, so a line arrives as e.g.
     * `-Tickets im VVK gibt es bei [www.eventim.de](www.eventim.de) -`. This strips a leading/trailing
     * list-item bullet and unwraps inline `[label](url)` links to their visible label (dropping the URL),
     * so `[www.eventim.de](www.eventim.de)` becomes `www.eventim.de`. Callers still drop the resulting
     * "Tickets im VVK …" promo line and bullet-only residue.
     */
    private fun cleanDescriptionLine(raw: String): String =
        raw
            .replaceFirst(LEADING_BULLET, "")
            .replace(TRAILING_BULLET, "")
            .replace(MARKDOWN_LINK) { it.groupValues[1] }
            .trim()

    /**
     * Extracts promoter names from an `.event-otitle` presenter line.
     *
     * Frannz over-titles carry the promoter as "<names> präsentiert:" /
     * "<names> präsentieren:" (e.g. "Loft & Flux FM präsentieren:"). The captured
     * names are split on comma / `&` / `/` / "und" into individual promoters. An
     * over-title without a presenter marker (e.g. "Live im Frannz Biergarten:") is
     * not a promoter and yields an empty list.
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
         * A copilot.events ticket-shop promo line to drop entirely. The connector word varies across
         * events ("… gibt es bei / unter / hier: <shop>"), so the match keys only on the stable opening
         * — a line starting with "Tickets" immediately followed by "im VVK" or "gibt es im VVK". That
         * anchoring keeps a genuine sentence that happens to mention tickets ("Tickets **für den** …
         * behalten ihre Gültigkeit, …"), whose second word is not "im"/"gibt".
         */
        private val TICKET_PROMO_LINE =
            Regex("""^Tickets\s+(?:im\s+VVK|gibt\s+es\s+im\s+VVK)\b""", RegexOption.IGNORE_CASE)

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
         * Formatter for "day. FullGermanMonth" (e.g. "11. Juli" → July 11).
         *
         * Case-insensitive with [Locale.GERMAN] month names (Januar … Dezember).
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
