package de.norm.events.scraper.saalchen

import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.attrAt
import de.norm.events.scraper.buildArtistsForEventType
import de.norm.events.scraper.cleanEventTitle
import de.norm.events.scraper.extractEventSlug
import de.norm.events.scraper.hrefAt
import de.norm.events.scraper.imgSrcAt
import de.norm.events.scraper.inferConcertVenueType
import de.norm.events.scraper.mapEventType
import de.norm.events.scraper.parseTime
import de.norm.events.scraper.resolveUrl
import de.norm.events.scraper.textAt
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * Pure HTML parser for Säälchen's programme, taken from the Holzmarkt site's shared
 * `/kalender` page.
 *
 * The calendar covers **the whole Holzmarkt site** — the Marktplatz flea markets and the
 * Holzmarkt 25 grounds as well — so rows are filtered on the `.location` span and only
 * `Säälchen` is kept. Month tabs are in-page anchors, so one fetch carries the whole programme.
 *
 * Each `.views-row` embeds an **AddToCalendar** widget whose `<var class="atc_*">` values are the
 * machine-readable part. `atc_date_start` is a **UTC** timestamp, converted here to
 * `Europe/Berlin`. Its `atc_description` holds a hand-typed `Datum / Einlass / Beginn / Ende /
 * Eintritt / Tickets` block followed by the event's prose — the source for the times, the price
 * and the description.
 *
 * @see SaalchenWebsiteImporter for the HTTP fetch orchestrator.
 * @see <a href="https://www.holzmarkt.com/kalender">Holzmarkt calendar</a>
 */
class SaalchenOverviewPageScraper {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses every Säälchen row from the shared calendar document.
     *
     * @param baseUrl the URL the document was fetched from, used to resolve the per-event
     *   `/veranstaltung/<slug>` links and the relative poster paths.
     * @return a list of [ScrapedEvent] instances, one per Säälchen row.
     */
    fun scrape(
        document: Document,
        baseUrl: String
    ): List<ScrapedEvent> {
        val rows = document.select(".views-row:has(article.node-event)")
        val ours = rows.filter { it.textAt(".location")?.equals(VENUE_LOCATION, ignoreCase = true) == true }
        logger.info { "Found ${ours.size} Säälchen row(s) among ${rows.size} on the Holzmarkt calendar" }

        @Suppress("TooGenericExceptionCaught") // Intentional: skip individual malformed rows without aborting the import
        return ours.mapNotNull { row ->
            try {
                parseRow(row, baseUrl)
            } catch (e: Exception) {
                logger.warn(e) { "Failed to parse Säälchen calendar row, skipping" }
                null
            }
        }
    }

    /** Parses a single `.views-row` into a [ScrapedEvent], or `null` when it has no link, title or date. */
    @Suppress("ReturnCount") // Guard clauses for the required href/title/date are clearer than nesting
    private fun parseRow(
        row: Element,
        baseUrl: String
    ): ScrapedEvent? {
        val href = row.hrefAt("h2 a") ?: row.selectFirst("h2 a[href]")?.attr("abs:href")
        if (href.isNullOrBlank()) return null
        val sourceUrl = resolveUrl(baseUrl, href)
        val slug = extractEventSlug(sourceUrl, "/veranstaltung/")

        val title = row.textAt("h2 a span")?.let { cleanEventTitle(it) }
        if (title.isNullOrBlank()) {
            logger.warn { "Säälchen row '$slug' has no title, skipping" }
            return null
        }
        // The AddToCalendar start is a UTC instant — the only machine-readable date on the page.
        val eventDate = parseUtcStartDate(row.textAt("var.atc_date_start"))
        if (eventDate == null) {
            logger.warn { "Säälchen row '$slug' has no parseable start date, skipping" }
            return null
        }

        val notice = parseNotice(row)
        val category = row.textAt(".event-category")
        val eventType = mapEventType(category) ?: inferConcertVenueType(title)
        val entrance = notice[ENTRANCE_LABEL]

        return ScrapedEvent(
            title = title,
            description = parseDescription(row),
            eventType = eventType,
            eventDate = eventDate,
            // The labelled prose wins: the venue's single `.doors` CMS field is filled
            // inconsistently, holding the Einlass on some nights and the Beginn on others.
            doorsTime = parseNoticeTime(notice[DOORS_LABEL]) ?: parseTime(row.textAt(".doors")?.substringBefore(" Uhr")),
            startTime = parseNoticeTime(notice[START_LABEL]),
            // Drupal serves the poster as a site-relative path, which imgSrcAt rejects as non-absolute.
            imageUrl = row.imgSrcAt(".image img") ?: row.attrAt(".image img", "src")?.let { resolveUrl(baseUrl, it) },
            sourceUrl = sourceUrl,
            sourceId = "${EventSource.SAALCHEN.sourceIdPrefix}$slug",
            ticketUrl = row.hrefAt("a.link-ticket"),
            // `.event-category` names a staging format (`Konzert`, `Kultur`, `Kunst`), not a musical
            // style, so it drives the event type only and is deliberately not stored as the genre —
            // the same call as Admiralspalast. The venue publishes no genre field of its own.
            pricePresale = parseSinglePrice(entrance),
            // The Eintritt line is free-form (tiered prices, "+ fees", a bare "30,00"), so the
            // venue's own wording is kept whenever it names one.
            priceNote = entrance,
            artists = buildArtistsForEventType(title, subtitle = null, eventType = eventType)
        )
    }

    /**
     * Converts the AddToCalendar `atc_date_start` UTC timestamp (`2026-11-14 19:00:00`) to the
     * Berlin calendar date. Returns `null` when the value is missing or unparseable.
     */
    private fun parseUtcStartDate(text: String?): LocalDate? {
        if (text.isNullOrBlank()) return null
        return try {
            LocalDateTime
                .parse(text.trim(), ATC_TIMESTAMP_FORMATTER)
                .atZone(ZoneId.of("UTC"))
                .withZoneSameInstant(BERLIN)
                .toLocalDate()
        } catch (_: DateTimeParseException) {
            null
        }
    }

    /**
     * Splits the AddToCalendar description's leading metadata block into its labelled lines
     * (`Einlass`, `Beginn`, `Eintritt`, …). The value is double-escaped HTML, so it is unescaped
     * and re-parsed before the `<br>`-separated lines are read.
     */
    private fun parseNotice(row: Element): Map<String, String> =
        noticeLines(row)
            .mapNotNull { line ->
                val label = line.substringBefore(':', "").trim().lowercase()
                val value = line.substringAfter(':', "").trim()
                if (label in NOTICE_LABELS && value.isNotBlank()) label to value else null
            }.toMap()

    /**
     * Reads the event's own prose — every line of the AddToCalendar description that is *not* one
     * of the [NOTICE_LABELS] metadata lines. Returns `null` when the venue wrote none, which is
     * currently the case for every Säälchen event.
     */
    private fun parseDescription(row: Element): String? =
        noticeLines(row)
            .filterNot { it.substringBefore(':', "").trim().lowercase() in NOTICE_LABELS }
            .joinToString("\n\n")
            .trim()
            .takeIf { it.isNotBlank() }

    /**
     * Splits the AddToCalendar description into its rendered lines.
     *
     * The value is double-escaped HTML, so it is re-parsed before being split on `<br>` and on
     * paragraph boundaries. Splitting rather than selecting `<p>` matters: the venue wraps the
     * block in a paragraph on most events but writes it bare on others (`Jimmy Sax`, `Main
     * Event`), where a `<p>`-scoped lookup would find nothing at all.
     */
    private fun noticeLines(row: Element): List<String> {
        val raw = row.textAt("var.atc_description") ?: return emptyList()
        return Jsoup
            .parse(raw)
            .body()
            .html()
            .split(LINE_BREAK_PATTERN)
            .map { Jsoup.parse(it).text().trim() }
            .filter { it.isNotBlank() }
    }
}

/**
 * Parses an `HH:mm`-ish time out of a hand-typed notice value, tolerating every spelling the
 * venue uses: `"20:00"`, `"19 Uhr"`, `"18:00 Uhr"`, and a trailing aside
 * (`"18:00 Uhr (Beginn der Vorentscheidung um 15:30 Uhr)"` → 18:00). Returns `null` when the
 * value names no time.
 */
internal fun parseNoticeTime(text: String?): LocalTime? =
    NOTICE_TIME_PATTERN.find(text.orEmpty())?.let { match ->
        // Branch 1 is "HH:mm", branch 2 the bare-hour "N Uhr" spelling.
        val hour = match.groupValues[1].ifEmpty { match.groupValues[3] }.toIntOrNull()
        val minute = match.groupValues[2].toIntOrNull() ?: 0
        hour?.let { runCatching { LocalTime.of(it, minute) }.getOrNull() }
    }

/**
 * Converts the free-form `Eintritt:` line to a number, but **only when it names exactly one
 * amount**. `"17,00 €"`, `"€40 + fees"`, `"30,00"` and `"36,95€"` all resolve; the venue's
 * three-tier `"15€ ermäßigt … 25€ Normalpreis … 35€ Förderticket"` deliberately does not, because
 * picking the first of three would store the concession price as the ticket price. The raw line is
 * kept in [ScrapedEvent.priceNote] either way.
 */
internal fun parseSinglePrice(text: String?): BigDecimal? {
    val value = text?.trim().orEmpty()
    val euroAmounts =
        EURO_AMOUNT_PATTERN
            .findAll(value)
            .map { match -> match.groupValues.drop(1).first { it.isNotEmpty() } }
            .toList()
    val single = euroAmounts.singleOrNull() ?: BARE_AMOUNT_PATTERN.matchEntire(value)?.groupValues?.get(1) ?: return null
    return single.replace(',', '.').toBigDecimalOrNull()
}

/** The `.location` value identifying this venue among the Holzmarkt site's shared calendar rows. */
private const val VENUE_LOCATION = "Säälchen"

/** Notice label for the doors time. */
private const val DOORS_LABEL = "einlass"

/** Notice label for the start time. */
private const val START_LABEL = "beginn"

/** Notice label for the admission price. */
private const val ENTRANCE_LABEL = "eintritt"

/**
 * Every label the venue uses in its AddToCalendar metadata block. A line starting with one of
 * these is metadata; anything else is the event's own prose.
 */
private val NOTICE_LABELS = setOf("datum", DOORS_LABEL, START_LABEL, "ende", ENTRANCE_LABEL, "tickets")

/** Splits the description on `<br>` and on paragraph boundaries. */
private val LINE_BREAK_PATTERN = Regex("""<br\s*/?>|</p>\s*<p[^>]*>""", RegexOption.IGNORE_CASE)

/** The AddToCalendar widget's timestamp format, always emitted in UTC. */
private val ATC_TIMESTAMP_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

/** Berlin, the zone every scraped venue sits in. */
private val BERLIN: ZoneId = ZoneId.of("Europe/Berlin")

/** Matches the first time in a notice value: `"20:00"` (branch 1) or the bare-hour `"19 Uhr"` (branch 2). */
private val NOTICE_TIME_PATTERN = Regex("""(\d{1,2}):(\d{2})|(\d{1,2})\s*Uhr""", RegexOption.IGNORE_CASE)

/** Matches a currency amount written either before or after the euro sign (`"€40"`, `"17,00 €"`). */
private val EURO_AMOUNT_PATTERN = Regex("""€\s*(\d+(?:[.,]\d{1,2})?)|(\d+(?:[.,]\d{1,2})?)\s*€""")

/** Matches a whole value that is nothing but a bare amount, the venue's `"30,00"` spelling. */
private val BARE_AMOUNT_PATTERN = Regex("""(\d+(?:[.,]\d{1,2})?)""")
