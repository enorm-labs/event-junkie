package de.norm.events.scraper.schlossparktheater

import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.GERMAN_MONTH_NAMES
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.WHITESPACE
import de.norm.events.scraper.blankToNull
import de.norm.events.scraper.hasFreeEntryPhrase
import de.norm.events.scraper.isNonArtistName
import de.norm.events.scraper.mapSkippingFailures
import de.norm.events.scraper.splitSegmentOnConjunctions
import de.norm.events.scraper.textAt
import de.norm.events.scraper.textLines
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime

/**
 * Pure HTML parser for one Schlosspark Theater month page (`/spielplan/<monat>-<jahr>.html`). Each
 * `div.hoverBox` is one performance; its `data-content` links the production page with the
 * performance as `?ID_Vorstellung=<id>`, which keys the row. A guest act is billed as
 * `Act <br>„Programme“`; a house production by its title alone. The site names no category, so
 * [schlossparkEventType] reads the title and the subtitle line. A `div.kursiv` note under the
 * subtitle carries a move or a voucher rule; `div.type` a performance badge.
 */
class SchlossparkTheaterMonthPageScraper {
    private val logger = KotlinLogging.logger {}

    fun scrape(
        document: Document,
        baseUrl: String
    ): List<ScrapedEvent> {
        val rows = document.select("#spielplan div.hoverBox[data-content]")
        logger.info { "Found ${rows.size} Schlosspark Theater performance(s) on $baseUrl" }
        return rows.mapSkippingFailures(logger, "Schlosspark Theater performance", ::parseRow)
    }

    private fun parseRow(row: Element): ScrapedEvent? {
        val link = row.absUrl("data-content").blankToNull() ?: return null
        val performance = PERFORMANCE_ID.find(link)?.groupValues?.get(1) ?: return null
        val eventDate = parseDate(row) ?: return null
        val heading =
            row
                .selectFirst("h2.headline_2")
                ?.textLines()
                .orEmpty()
                .map { it.replace(WHITESPACE, " ") }
        val billing = SchlossparkBilling.of(heading) ?: return null
        val details = row.selectFirst(".detailBox > div")
        val subtitle = details?.let(::subtitleOf)
        val badge = row.textAt("div.type span")
        val eventType = schlossparkEventType(billing.title, subtitle)
        val priceText = row.textAt("p.price a.euro")
        val prices = AMOUNT.findAll(priceText.orEmpty()).map { BigDecimal(it.value.replace(',', '.')) }.toList()
        return ScrapedEvent(
            title = billing.title,
            subtitle = subtitle,
            eventType = eventType ?: EventType.SHOW.name,
            typeIsFallback = eventType == null,
            eventDate = eventDate,
            startTime = parseTime(row),
            imageUrl = row.selectFirst(".imageBox img[src]")?.absUrl("src")?.blankToNull(),
            sourceUrl = link.substringBefore('?'),
            sourceId = "${EventSource.SCHLOSSPARK_THEATER.sourceIdPrefix}$performance",
            ticketUrl = row.selectFirst("a.ticketlink[href]")?.absUrl("href")?.blankToNull(),
            pricePresale = prices.minOrNull(),
            // `22,00–39,50 €` spans the seat groups.
            priceNote = priceText?.takeIf { prices.size > 1 },
            free = hasFreeEntryPhrase(badge),
            statusNote = details?.textAt("div.kursiv")?.takeUnless { VOUCHER_NOTE.containsMatchIn(it) },
            artists = billing.acts(eventType)
        )
    }

    /** The lines under the heading, without the `mehr` link and the note in `div.kursiv`. */
    private fun subtitleOf(details: Element): String? =
        details
            .select("p, h3")
            .filter { line -> line.selectFirst("a.mehrlink") == null && line.closest("div.kursiv") == null && line.select("p, h3").size == 1 }
            .mapNotNull { it.text().blankToNull() }
            .joinToString(" · ")
            .blankToNull()

    private fun parseDate(row: Element): LocalDate? {
        val day = row.textAt(".dayBox .day")?.trimEnd('.')?.toIntOrNull() ?: return null
        val month = GERMAN_MONTH_NAMES.indexOfFirst { it.equals(row.textAt(".dayBox .month"), ignoreCase = true) }.takeIf { it >= 0 } ?: return null
        val year = row.textAt(".dayBox .year")?.toIntOrNull() ?: return null
        return LocalDate.of(year, month + 1, day)
    }

    /** `20<sup>00</sup>`: the hour as own text, the minutes in the `sup`. */
    private fun parseTime(row: Element): LocalTime? {
        val box = row.selectFirst(".timeBox") ?: return null
        val hour = box.ownText().trim().toIntOrNull() ?: return null
        val minute =
            box
                .selectFirst("sup")
                ?.text()
                ?.trim()
                ?.toIntOrNull() ?: 0
        return runCatching { LocalTime.of(hour, minute) }.getOrNull()
    }

    private companion object {
        val PERFORMANCE_ID = Regex("""[?&]ID_Vorstellung=(\d+)""")
        val AMOUNT = Regex("""\d+,\d{2}""")
        val VOUCHER_NOTE = Regex("""abo-gutschein""", RegexOption.IGNORE_CASE)
    }
}

/**
 * A row's heading split at its `<br>`: the act and its programme for a guest, or the production
 * title alone. A programme in quotes marks the first line as the act.
 */
internal class SchlossparkBilling private constructor(
    private val first: String,
    private val programme: String?
) {
    private val quoted = programme?.firstOrNull()?.let { QUOTE.matches(it.toString()) } == true
    private val punctuated = first.endsWith('–') || first.endsWith('-') || first.endsWith(':')

    val title: String =
        when {
            programme == null -> first
            punctuated -> "$first ${unquoted(programme)}"
            else -> "$first: ${unquoted(programme)}"
        }

    /**
     * The acts the heading bills: the first line before a quoted programme, or before any programme
     * when the night is a concert. An `Einführungsmatinée` is the house's talk, so it bills nobody.
     */
    fun acts(eventType: String?): List<ScrapedArtist> {
        val billsAct = programme != null && !punctuated && !MATINEE.containsMatchIn(first)
        if (!billsAct || !(quoted || eventType == EventType.CONCERT.name)) return emptyList()
        return splitSegmentOnConjunctions(first.replace(UNNAMED_COMPANY, ""))
            .mapNotNull { it.blankToNull() }
            .filterNot(::isNonArtistName)
            .map { ScrapedArtist(name = it, titleDerived = true) }
    }

    companion object {
        private val QUOTE = Regex("[„“”\"]")
        private val MATINEE = Regex("""matin[ée]e""", RegexOption.IGNORE_CASE)
        private val UNNAMED_COMPANY = Regex("""\s+(?:&|und)\s+(?:sein\s+|ihr\s+)?(?:band|orchester)$""", RegexOption.IGNORE_CASE)

        private fun unquoted(programme: String): String = programme.replace(QUOTE, "").replace(WHITESPACE, " ").trim()

        fun of(lines: List<String>): SchlossparkBilling? {
            val first = lines.firstOrNull()?.blankToNull() ?: return null
            return SchlossparkBilling(first, lines.drop(1).joinToString(" ").blankToNull())
        }
    }
}

/**
 * The type the title and subtitle name, or null when none does: a reading or a talk, then
 * Kabarett, then a play, then a concert, then any other staged show. A play comes before a concert
 * because a musical comedy names its `Hits`; a concert before a show because a band's Christmas
 * night is billed as a `Show`. A `Komödie` is a play, never `COMEDY`.
 */
internal fun schlossparkEventType(
    title: String,
    subtitle: String?
): String? {
    val text = "$title ${subtitle.orEmpty()}"
    return when {
        READING.containsMatchIn(text) -> EventType.READING.name
        COMEDY.containsMatchIn(text) -> EventType.COMEDY.name
        PLAY.containsMatchIn(text) -> EventType.SHOW.name
        CONCERT.containsMatchIn(text) -> EventType.CONCERT.name
        SHOW.containsMatchIn(text) -> EventType.SHOW.name
        else -> null
    }
}

private val READING =
    Regex("""lesung|\bliest\b|gespräch|talk|podcast|matin[ée]e|autor(?:in)?\b|moderation""", RegexOption.IGNORE_CASE)
private val COMEDY = Regex("""kabarett|comedy|satire|stand-up""", RegexOption.IGNORE_CASE)
private val PLAY = Regex("""komödie|kammerspiel|hörspiel|schauspiel|theaterstück""", RegexOption.IGNORE_CASE)
private val CONCERT =
    Regex(
        """konzert|orchester|\bband\b|tenöre|lieder|\bhits\b|tour\b|swing|chanson|rockmusik|musiker|musikalisch|tribut""",
        RegexOption.IGNORE_CASE
    )
private val SHOW = Regex("""show|revue|musical""", RegexOption.IGNORE_CASE)
