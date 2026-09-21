package de.norm.events.scraper.saalchen

import de.norm.events.scraper.BERLIN
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.attrAt
import de.norm.events.scraper.buildArtistsForEventType
import de.norm.events.scraper.cleanEventTitle
import de.norm.events.scraper.endOn
import de.norm.events.scraper.extractEventSlug
import de.norm.events.scraper.hrefAt
import de.norm.events.scraper.imgSrcAt
import de.norm.events.scraper.inferConcertVenueType
import de.norm.events.scraper.isNonArtistName
import de.norm.events.scraper.mapEventType
import de.norm.events.scraper.parseTime
import de.norm.events.scraper.resolveUrl
import de.norm.events.scraper.splitSupportActs
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
 * Pure HTML parser for Säälchen's programme, from the Holzmarkt site's shared `/kalender` page.
 *
 * The calendar covers **the whole Holzmarkt site** — the Marktplatz flea markets and the
 * Holzmarkt 25 grounds too — so rows are filtered on the `.location` span and only `Säälchen`
 * is kept. Month tabs are in-page anchors, so one fetch carries the whole programme.
 *
 * Each `.views-row` embeds an **AddToCalendar** widget whose `<var class="atc_*">` values are
 * the machine-readable part. `atc_date_start` is a **UTC** timestamp, converted to
 * `Europe/Berlin`. `atc_description` holds a hand-typed `Datum / Einlass / Beginn / Ende /
 * Eintritt / Tickets` block then the prose — the source for times and price. The row's
 * `.body-content` renders the same prose in full where `atc_description` holds only its first
 * paragraph, so the description comes from there; a festival names its acts there in one
 * sentence ending `mit: <acts>.` (`Das diesjährige Line-up verspricht musikalische Vielfalt
 * mit: Catch The Young, Bongjeingan, kimseungjoo und Chang Kiha.`), read as the line-up (#1584).
 *
 * @see SaalchenWebsiteImporter for the HTTP fetch orchestrator.
 * @see <a href="https://www.holzmarkt.com/kalender">Holzmarkt calendar</a>
 */
class SaalchenOverviewPageScraper {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses every Säälchen row from the shared calendar, one [ScrapedEvent] per row.
     *
     * @param baseUrl the URL the document was fetched from, for the `/veranstaltung/<slug>` links
     * and the relative poster paths.
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

    /** Parses one `.views-row` into a [ScrapedEvent], or `null` without link, title or date. */
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
        val description = parseDescription(row)
        val startTime = parseNoticeTime(notice[START_LABEL])
        val endTime = parseNoticeTime(notice[END_LABEL])

        return ScrapedEvent(
            title = title,
            description = description,
            eventType = eventType,
            eventDate = eventDate,
            // The labelled prose wins: the single `.doors` CMS field is filled inconsistently, the Einlass
            // on some nights and the Beginn on others.
            doorsTime = parseNoticeTime(notice[DOORS_LABEL]) ?: parseTime(row.textAt(".doors")?.substringBefore(" Uhr")),
            startTime = startTime,
            endTime = endTime,
            endDate = endTime?.let { endOn(eventDate, startTime, it) },
            // Drupal serves the poster as a site-relative path, which imgSrcAt rejects as non-absolute.
            imageUrl = row.imgSrcAt(".image img") ?: row.attrAt(".image img", "src")?.let { resolveUrl(baseUrl, it) },
            sourceUrl = sourceUrl,
            sourceId = "${EventSource.SAALCHEN.sourceIdPrefix}$slug",
            ticketUrl = row.hrefAt("a.link-ticket"),
            // `.event-category` names a staging format (`Konzert`, `Kultur`, `Kunst`), not a style, so it
            // drives the event type only and is not stored as the genre — Admiralspalast's call. The venue
            // publishes no genre field.
            pricePresale = parseSinglePrice(entrance),
            // The Eintritt line is free-form (tiered prices, "+ fees", a bare "30,00"), so the venue's own
            // wording is kept whenever it names one.
            priceNote = entrance,
            artists =
                (buildArtistsForEventType(title, subtitle = null, eventType = eventType) + parseLineupSentence(row))
                    .distinctBy { it.name.lowercase() }
        )
    }

    /** The acts a `… mit: A, B und C.` sentence in the row's rendered prose names, as headliners. */
    private fun parseLineupSentence(row: Element): List<ScrapedArtist> =
        LINEUP_SENTENCE
            .find(row.textAt(".body-content").orEmpty())
            ?.groupValues
            ?.get(1)
            ?.let(::splitSupportActs)
            .orEmpty()
            .filterNot(::isNonArtistName)
            .map { ScrapedArtist(name = it, role = "HEADLINER") }

    /**
     * The AddToCalendar `atc_date_start` UTC timestamp (`2026-11-14 19:00:00`) as the Berlin
     * calendar date. `null` when missing or unparseable.
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
     * The AddToCalendar description's leading metadata block as labelled lines (`Einlass`,
     * `Beginn`, `Eintritt`, …). The value is double-escaped HTML, unescaped and re-parsed before
     * the `<br>`-separated lines are read.
     */
    private fun parseNotice(row: Element): Map<String, String> =
        noticeLines(row)
            .mapNotNull { line ->
                val label = line.substringBefore(':', "").trim().lowercase()
                val value = line.substringAfter(':', "").trim()
                if (label in NOTICE_LABELS && value.isNotBlank()) label to value else null
            }.toMap()

    /**
     * The event's own prose — every line of the rendered `.body-content` that is *not* one of the
     * [NOTICE_LABELS] metadata lines (#1647). The AddToCalendar description holds the same text cut
     * to its first paragraph and is the fallback for a row with no body.
     */
    private fun parseDescription(row: Element): String? =
        (bodyLines(row).ifEmpty { noticeLines(row) })
            .filterNot { it.substringBefore(':', "").trim().lowercase() in NOTICE_LABELS }
            .joinToString("\n\n")
            .trim()
            .takeIf { it.isNotBlank() }

    /**
     * The rendered `.body-content` split into lines, as [noticeLines] splits the escaped copy. The
     * widget and the ticket button sit inside the same div, so they are cut from a copy first —
     * their `<var>` values would otherwise read as prose.
     */
    private fun bodyLines(row: Element): List<String> =
        row
            .selectFirst(".body-content")
            ?.clone()
            ?.also { it.select(".service-links, .addtocalendar, var, a.link-ticket").remove() }
            ?.html()
            ?.split(LINE_BREAK_PATTERN)
            ?.map { Jsoup.parse(it).text().trim() }
            ?.filter { it.isNotBlank() }
            .orEmpty()

    /**
     * The AddToCalendar description split into rendered lines. Double-escaped HTML, re-parsed
     * before splitting on `<br>` and paragraph boundaries. Splitting rather than selecting `<p>`
     * matters: the block is wrapped in a paragraph on most events but bare on others (`Jimmy
     * Sax`, `Main Event`), where a `<p>`-scoped lookup finds nothing.
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
 * An `HH:mm`-ish time out of a hand-typed notice value, in every spelling: `"20:00"`, `"19
 * Uhr"`, `"18:00 Uhr"`, and a trailing aside (`"18:00 Uhr (Beginn der Vorentscheidung um 15:30
 * Uhr)"` → 18:00). `null` when the value names no time.
 */
private fun parseNoticeTime(text: String?): LocalTime? =
    NOTICE_TIME_PATTERN.find(text.orEmpty())?.let { match ->
        // Branch 1 is "HH:mm", branch 2 the bare-hour "N Uhr" spelling.
        val hour = match.groupValues[1].ifEmpty { match.groupValues[3] }.toIntOrNull()
        val minute = match.groupValues[2].toIntOrNull() ?: 0
        hour?.let { runCatching { LocalTime.of(it, minute) }.getOrNull() }
    }

/**
 * The free-form `Eintritt:` line as a number, **only when it names exactly one amount** — or
 * labels one as the day ticket. `"17,00 €"`, `"€40 + fees"`, `"30,00"` and `"36,95€"` resolve,
 * as does `"Tagesticket: 13 € / 2-Tagesticket: 20 €"`, where the `Tagesticket` figure is one
 * day's cost (#1584). The three-tier `"15€ ermäßigt … 25€ Normalpreis … 35€ Förderticket"`
 * deliberately does not: the first of three would store the concession as the ticket price.
 * The raw line is kept in [ScrapedEvent.priceNote] either way.
 */
private fun parseSinglePrice(text: String?): BigDecimal? {
    val value = text?.trim().orEmpty()
    val euroAmounts =
        EURO_AMOUNT_PATTERN
            .findAll(value)
            .map { match -> match.groupValues.drop(1).first { it.isNotEmpty() } }
            .toList()
    val amount =
        DAY_TICKET_PATTERN.find(value)?.groupValues?.get(1)
            ?: euroAmounts.singleOrNull()
            ?: BARE_AMOUNT_PATTERN.matchEntire(value)?.groupValues?.get(1)
    return amount?.replace(',', '.')?.toBigDecimalOrNull()
}

/** The `.location` value identifying this venue among the Holzmarkt site's shared calendar rows. */
private const val VENUE_LOCATION = "Säälchen"

/** Notice label for the doors time. */
private const val DOORS_LABEL = "einlass"

/** Notice label for the start time. */
private const val START_LABEL = "beginn"

/** Notice label for the end time. */
private const val END_LABEL = "ende"

/** Notice label for the admission price. */
private const val ENTRANCE_LABEL = "eintritt"

/** The `Tagesticket: 13 €` figure of a tiered festival tariff — the day's own price, not a concession. */
private val DAY_TICKET_PATTERN = Regex("""(?<!\d-)\btagesticket:?\s*(\d+(?:[.,]\d{1,2})?)\s*€""", RegexOption.IGNORE_CASE)

/** The prose sentence that names a festival's line-up, up to its full stop. */
private val LINEUP_SENTENCE = Regex("""line-?up\b[^.:]*\bmit:\s*([^.]+)\.""", RegexOption.IGNORE_CASE)

/**
 * Every label the venue uses in its AddToCalendar metadata block. A line starting with one is
 * metadata; anything else is the event's own prose.
 */
private val NOTICE_LABELS = setOf("datum", DOORS_LABEL, START_LABEL, END_LABEL, ENTRANCE_LABEL, "tickets")

/** Splits the description on `<br>` and on paragraph boundaries. */
private val LINE_BREAK_PATTERN = Regex("""<br\s*/?>|</p>\s*<p[^>]*>""", RegexOption.IGNORE_CASE)

/** The AddToCalendar widget's timestamp format, always emitted in UTC. */
private val ATC_TIMESTAMP_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

/** The first time in a notice value: `"20:00"` (branch 1) or the bare-hour `"19 Uhr"` (branch 2). */
private val NOTICE_TIME_PATTERN = Regex("""(\d{1,2}):(\d{2})|(\d{1,2})\s*Uhr""", RegexOption.IGNORE_CASE)

/** A currency amount written before or after the euro sign (`"€40"`, `"17,00 €"`). */
private val EURO_AMOUNT_PATTERN = Regex("""€\s*(\d+(?:[.,]\d{1,2})?)|(\d+(?:[.,]\d{1,2})?)\s*€""")

/** A whole value that is nothing but a bare amount, the venue's `"30,00"` spelling. */
private val BARE_AMOUNT_PATTERN = Regex("""(\d+(?:[.,]\d{1,2})?)""")
