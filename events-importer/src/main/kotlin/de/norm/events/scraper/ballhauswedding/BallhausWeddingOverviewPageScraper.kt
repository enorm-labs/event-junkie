package de.norm.events.scraper.ballhauswedding

import de.norm.events.event.EventStatus
import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.buildArtistsForEventType
import de.norm.events.scraper.endOn
import de.norm.events.scraper.resolveUrl
import de.norm.events.slug.SlugGenerator
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.math.BigDecimal
import java.time.DateTimeException
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

/**
 * Pure parser for the Ballhaus Wedding programme, a Wix page with one rich-text block per month. Each entry is a run
 * of paragraphs: a date line (`Do 1.10. 19:30 Uhr Vilma Remezaite singt Barbra Streisand - Konzert`), then free
 * text, a price line (`23 € Vorverkauf - 27 € AK`) and a `Tickets` link.
 *
 * A date has no year. Each month block takes the year that most of its printed weekdays fall in, so a stale block
 * (a past September after June) still dates right, and one misprinted weekday is outvoted.
 * A date line that ends in a preposition or an article runs on into the next paragraph, which joins the title.
 *
 * The site names no category, so the type comes from the house's own words: dance socials are parties, a slam or a
 * lecture is a reading, improv, dinner theatre and variety are shows. A bare `<name> - Konzert` entry bills the name.
 * Any other title is a programme name, and so is a concert title with a text line under it (`Hotel de Pologne`, then
 * `Tate Mama - eine musikalische Reise`), so neither bills anybody.
 */
class BallhausWeddingOverviewPageScraper {
    private val logger = KotlinLogging.logger {}

    fun scrape(
        document: Document,
        baseUrl: String,
        today: LocalDate
    ): List<ScrapedEvent> {
        val entries = mutableListOf<Entry>()
        var headingYear: Int? = null
        for (block in document.select("div[data-testid=richTextElement]")) {
            val paragraphs = block.select("p, h1, h2, h3, h4, h5, h6").map { Paragraph(clean(it.text()), ticketLinks(it)) }.filter { it.text.isNotEmpty() }
            if (paragraphs.firstOrNull()?.text?.let { it in MONTHS || YEAR_HEADING.matches(it) } != true) continue
            headingYear = paragraphs.firstNotNullOfOrNull { it.text.takeIf(YEAR_HEADING::matches)?.toInt() } ?: headingYear
            entries += readBlock(paragraphs, blockYear(paragraphs.mapNotNull { DATE_LINE.find(it.text) }, headingYear, today))
        }
        val upcoming = entries.filterNot { it.date.isBefore(today) }
        logger.info { "Found ${entries.size} Ballhaus Wedding entr(ies), ${upcoming.size} upcoming" }
        return upcoming.map { toEvent(it, baseUrl) }.distinctBy { it.sourceId }
    }

    /** One month block's entries: a date line opens one, and every later paragraph up to the next belongs to it. */
    private fun readBlock(
        paragraphs: List<Paragraph>,
        year: Int
    ): List<Entry> {
        val entries = mutableListOf<Entry>()
        var cancelNext = false
        var runOn = false
        for ((index, paragraph) in paragraphs.withIndex()) {
            val dateLine = DATE_LINE.find(paragraph.text)
            when {
                runOn -> {
                    runOn = false
                }

                CANCELLED_NOTE.matches(paragraph.text) -> {
                    cancelNext = true
                }

                dateLine != null -> {
                    val entry = entryOf(dateLine, paragraphs.getOrNull(index + 1)?.text, year, cancelNext)
                    if (entry == null) logger.warn { "Ballhaus Wedding date line '${paragraph.text}' has no valid date, skipping" }
                    entry?.ticketLinks?.addAll(paragraph.ticketLinks)
                    entries.addAll(listOfNotNull(entry))
                    runOn = entry?.runsOn == true
                    cancelNext = false
                }

                entries.isNotEmpty() -> {
                    entries.last().lines += paragraph.text
                    entries.last().ticketLinks += paragraph.ticketLinks
                }
            }
        }
        return entries
    }

    private fun ticketLinks(paragraph: Element): List<String> = paragraph.select("a[href]").filter { it.text().trim() == "Tickets" }.map { it.attr("href") }

    private fun entryOf(
        match: MatchResult,
        nextLine: String?,
        year: Int,
        cancelled: Boolean
    ): Entry? {
        fun group(name: String): String = match.groups[name]?.value.orEmpty()
        val date = safeDate(year, group("month").toInt(), group("day").toInt()) ?: return null
        val rest = group("title")
        val runsOn = DANGLING_WORD.containsMatchIn(rest) && nextLine != null
        return Entry(
            date = date,
            start = clock(group("startHour"), group("startMinute")),
            end = clock(group("endHour"), group("endMinute")),
            title = if (runsOn) "$rest $nextLine" else rest,
            cancelled = cancelled || "abgesagt" in rest.lowercase(),
            runsOn = runsOn
        )
    }

    private fun toEvent(
        entry: Entry,
        baseUrl: String
    ): ScrapedEvent {
        val text = entry.lines
        val concertName = CONCERT_SUFFIX.find(entry.title)?.let { entry.title.substring(0, it.range.first).trim() }?.ifEmpty { null }
        val title = concertName ?: entry.title
        val description = text.filterNot(::isNoise).map { it.replace(MORE_INFO, "").trim() }.filter { it.isNotEmpty() }
        val ticketUrl = entry.ticketLinks.firstOrNull { !it.startsWith("mailto:") }?.let { resolveUrl(baseUrl, it) }
        val prices = text.filter { "€" in it }.joinToString(" ")
        val billed = concertName?.takeIf { BARE_NAME.matches(it) && description.isEmpty() }
        return ScrapedEvent(
            title = title,
            description = joinWrapped(description).ifBlank { null },
            eventType = eventTypeOf(entry.title, text),
            typeIsFallback = true,
            eventDate = entry.date,
            startTime = entry.start,
            endDate = entry.end?.let { endOn(entry.date, entry.start, it) },
            endTime = entry.end,
            sourceUrl = ticketUrl?.takeIf { EVENT_PAGE.containsMatchIn(it) } ?: baseUrl,
            sourceId = "${EventSource.BALLHAUS_WEDDING.sourceIdPrefix}${entry.date}-${SlugGenerator.slugify(title)}",
            ticketUrl = ticketUrl,
            pricePresale = amount(prices, PRESALE) ?: amount(prices, REGULAR) ?: amount(prices, PRESALE_LABEL_FIRST),
            priceBoxOffice = amount(prices, BOX_OFFICE) ?: amount(prices, BOX_OFFICE_LABEL_FIRST),
            free = text.any { FREE.matches(it) },
            status = if (entry.cancelled) EventStatus.CANCELLED.name else EventStatus.SCHEDULED.name,
            artists = billed?.let { buildArtistsForEventType(it, null, EventType.CONCERT.name) }.orEmpty()
        )
    }

    private class Paragraph(
        val text: String,
        val ticketLinks: List<String>
    )

    private class Entry(
        val date: LocalDate,
        val start: LocalTime?,
        val end: LocalTime?,
        val title: String,
        val cancelled: Boolean,
        val runsOn: Boolean
    ) {
        val lines = mutableListOf<String>()
        val ticketLinks = mutableListOf<String>()
    }
}

/** The house's own words for its formats; the first that matches wins, so a `Stummfilmkino mit Livemusik` is a screening. */
private fun eventTypeOf(
    title: String,
    text: List<String>
): String {
    val head = title.lowercase()
    val lead = text.take(2).joinToString(" ").lowercase()
    return when {
        SCREENING.containsMatchIn(head) -> EventType.SCREENING.name
        "flohmarkt" in head -> EventType.OTHER.name
        "konzert" in head -> EventType.CONCERT.name
        DANCE.containsMatchIn(head) -> EventType.PARTY.name
        READING.containsMatchIn(head) -> EventType.READING.name
        STAGE_SHOW.containsMatchIn(head) -> EventType.SHOW.name
        "konzert" in lead || LIVE_MUSIC.containsMatchIn(head) -> EventType.CONCERT.name
        else -> EventType.OTHER.name
    }
}

/**
 * The year that most of a month block's printed weekdays fall in, among the years around today. One wrong weekday
 * (`Mi 31.12.` on a Thursday) is outvoted by its neighbours. A tie goes to the latest `20xx` heading, else this year.
 */
private fun blockYear(
    dateLines: List<MatchResult>,
    headingYear: Int?,
    today: LocalDate
): Int {
    val votes =
        (today.year - 1..today.year + 1).associateWith { year ->
            dateLines.count { line ->
                val (weekday, day, month) = line.destructured
                safeDate(year, month.toInt(), day.toInt())?.dayOfWeek == WEEKDAYS[weekday]
            }
        }
    val best = votes.values.max()
    val winners = votes.filterValues { it == best }.keys
    return winners.singleOrNull() ?: headingYear?.takeIf { it in winners } ?: today.year
}

private fun safeDate(
    year: Int,
    month: Int,
    day: Int
): LocalDate? =
    try {
        LocalDate.of(year, month, day)
    } catch (_: DateTimeException) {
        null
    }

private fun clock(
    hour: String,
    minute: String
): LocalTime? = hour.toIntOrNull()?.let { runCatching { LocalTime.of(it, minute.toIntOrNull() ?: 0) }.getOrNull() }

private fun amount(
    text: String,
    pattern: Regex
): BigDecimal? =
    pattern
        .find(text)
        ?.groupValues
        ?.get(1)
        ?.replace(',', '.')
        ?.toBigDecimalOrNull()

/** A line the description leaves out: a price, the `Tickets` link, a bare web address, or a contact line with an e-mail address. */
private fun isNoise(line: String): Boolean =
    line == "Tickets" || BARE_URL.matches(line) || EMAIL.containsMatchIn(line) || FREE.matches(line) || ("€" in line && PRICE_WORD.containsMatchIn(line))

/** Wix sets each visual line as a paragraph, so a line that stops mid-sentence continues on the next. */
private fun joinWrapped(lines: List<String>): String =
    lines.fold("") { text, line ->
        when {
            text.isEmpty() -> line
            MID_SENTENCE.containsMatchIn(text) -> "$text $line"
            else -> "$text\n$line"
        }
    }

private fun clean(text: String): String =
    text
        .replace('\u00A0', ' ')
        .replace("\u200B", "")
        .replace(SPACES, " ")
        .trim()

private val MONTHS = setOf("Januar", "Februar", "März", "April", "Mai", "Juni", "Juli", "August", "September", "Oktober", "November", "Dezember")
private val WEEKDAYS =
    mapOf(
        "Mo" to DayOfWeek.MONDAY,
        "Di" to DayOfWeek.TUESDAY,
        "Mi" to DayOfWeek.WEDNESDAY,
        "Do" to DayOfWeek.THURSDAY,
        "Fr" to DayOfWeek.FRIDAY,
        "Sa" to DayOfWeek.SATURDAY,
        "So" to DayOfWeek.SUNDAY
    )
private val YEAR_HEADING = Regex("""20\d\d""")
private val SPACES = Regex("""\s+""")

/** `Do 1.10. 19:30 Uhr <title>`, `Di, 6.10. 20 Uhr`, `So 27.6. 13 - 16 Uhr`, `Di 15.12. 19:30 <title>`. */
private val DATE_LINE =
    Regex(
        """^(Mo|Di|Mi|Do|Fr|Sa|So)\.?,?\s+(?<day>\d{1,2})\.(?<month>\d{1,2})\.?\s+""" +
            """(?:(?<startHour>\d{1,2})(?::(?<startMinute>\d{2}))?(?:\s*-\s*(?<endHour>\d{1,2})(?::(?<endMinute>\d{2}))?)?\s*(?:Uhr)?\s+)?""" +
            """(?<title>.+)$"""
    )
private val CANCELLED_NOTE = Regex("""(?:leider\s+)?abgesagt\s*:?""", RegexOption.IGNORE_CASE)
private val DANGLING_WORD = Regex("""\s(?:im|in|am|an|der|die|das|des|dem|den|und|mit|von|vom|zu|zum|zur|für|auf|aus|bei)$""")
private val CONCERT_SUFFIX = Regex("""\s*-\s*Konzert$""")

/** A name without a tagline: no further dash, colon or `&`, and no `singt`, `mit` or `und` joining a second name. */
private val BARE_NAME = Regex("""(?!.*\b(?:singt|mit|und)\b)[^-–—:&]+""")
private val EVENT_PAGE = Regex("""ballhauswedding\.de/details-registrierung/""")

private val SCREENING = Regex("""stummfilm|kino""")
private val DANCE =
    Regex("""tango|milonga|discofox|bachata|timba|salsa|tanztee|tanz in den mai|\wball\b|seniorendiskothek|party|masken|soirée|vertigo""")
private val READING = Regex("""slam|lesung|lecture""")
private val STAGE_SHOW = Regex("""theater|gruseldinner|krimi|varieté|show|cabaret|burlesque|magic""")
private val LIVE_MUSIC = Regex("""live-?musik""")

private val PRESALE = Regex("""(\d+(?:,\d+)?)\s*€\s*(?:Vorverkauf|VVK)""")
private val REGULAR = Regex("""Regulär:?\s*(\d+(?:,\d+)?)\s*€""")
private val PRESALE_LABEL_FIRST = Regex("""(?:Vorverkauf|VVK)\s*:?\s*(?:ab\s*)?(\d+(?:,\d+)?)\s*€""")
private val BOX_OFFICE = Regex("""(\d+(?:,\d+)?)\s*€\s*AK\b""")
private val BOX_OFFICE_LABEL_FIRST = Regex("""\bAK\s*:?\s*(\d+(?:,\d+)?)\s*€""")
private val FREE = Regex("""Eintritt frei""", RegexOption.IGNORE_CASE)
private val PRICE_WORD = Regex("""Vorverkauf|VVK|\bAK\b|Regulär|Early Bird""")
private val MORE_INFO = Regex("""\s*-?\s*mehr Infos$""")
private val MID_SENTENCE = Regex("""[\p{Ll},&]$""")
private val BARE_URL = Regex("""(?:https?://|www\.)\S+""")
private val EMAIL = Regex("""[\w.+-]+@[\w-]+\.[\w.]+""")
