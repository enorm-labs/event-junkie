package de.norm.events.scraper.labsaal

import de.norm.events.event.EventType
import de.norm.events.scraper.BERLIN
import de.norm.events.scraper.CalendarProperty
import de.norm.events.scraper.DOORS_LABELS
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.blankToNull
import de.norm.events.scraper.calendarEvents
import de.norm.events.scraper.cleanEventTitle
import de.norm.events.scraper.decodeHtmlEntities
import de.norm.events.scraper.extractEventSlug
import de.norm.events.scraper.hasFreeEntryPhrase
import de.norm.events.scraper.htmlParagraphText
import de.norm.events.scraper.inferUnmarkedTitleType
import de.norm.events.scraper.labelledClock
import de.norm.events.scraper.mapSkippingFailures
import de.norm.events.scraper.orderDoorsBeforeStart
import de.norm.events.scraper.parseLabelledPrices
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Pure parser for the Modern Events Calendar iCalendar feed of the LabSaal. The feed also carries
 * the society's weekly groups and courses; only the `Alle Veranstaltungen` category is the
 * programme ([isProgramme]). The site names no format, so the type comes from the title, then from
 * a format the description names, then from a music cue; a night with none is typed other.
 */
class LabsaalCalendarScraper {
    private val logger = KotlinLogging.logger {}

    fun scrape(calendar: String): List<ScrapedEvent> {
        val entries = calendarEvents(calendar)
        logger.info { "Found ${entries.size} LabSaal calendar entr(y/ies)" }
        return entries.mapSkippingFailures(logger, "LabSaal calendar entry") { parseEntry(it) }
    }

    private fun parseEntry(entry: Map<String, CalendarProperty>): ScrapedEvent? {
        val title =
            entry["SUMMARY"]
                ?.text
                ?.let(::decodeHtmlEntities)
                ?.let(::cleanEventTitle)
                .blankToNull()
        val url = entry["URL"]?.value.blankToNull()
        val start = entry["DTSTART"]?.let(::siteTime)
        if (title == null || url == null || start == null) {
            logger.warn { "LabSaal calendar entry '$title' lacks a title, URL or start, skipping" }
            return null
        }
        val categories =
            entry["CATEGORIES"]
                ?.text
                .orEmpty()
                .split(',')
                .mapNotNull { it.blankToNull() }
        if (!isProgramme(title, categories)) return null
        val rawDescription = entry["DESCRIPTION"]?.text
        val description = htmlParagraphText(rawDescription?.replace(LINK_NOTE, ""))
        val (doors, startTime) = orderDoorsBeforeStart(labelledClock(description, DOORS_LABELS), start.toLocalTime())
        val prices = parseLabelledPrices(description)
        val hasPrice = prices.presale != null || prices.boxOffice != null
        val eventType = eventType(title, description)
        return ScrapedEvent(
            title = title,
            description = description,
            eventType = eventType,
            typeIsFallback = eventType == EventType.OTHER.name && MARKET.containsMatchIn(title).not(),
            eventDate = start.toLocalDate(),
            doorsTime = doors,
            startTime = startTime?.takeUnless { it == doors },
            imageUrl = entry["ATTACH"]?.takeIf { it.params["FMTTYPE"]?.startsWith("image/") == true }?.value,
            sourceUrl = url,
            sourceId = "${EventSource.LABSAAL.sourceIdPrefix}${extractEventSlug(url)}",
            ticketUrl = rawDescription?.let { TICKET_LINK.find(it)?.groupValues?.get(1) },
            pricePresale = prices.presale,
            priceBoxOffice = prices.boxOffice,
            free = !hasPrice && hasFreeEntryPhrase(description)
        )
    }

    private fun eventType(
        title: String,
        description: String?
    ): String =
        TITLE_FORMATS.entries.firstOrNull { (cue, _) -> cue.containsMatchIn(title) }?.value
            ?: inferUnmarkedTitleType(title).takeUnless { it == EventType.OTHER.name }
            ?: DESCRIPTION_FORMATS.entries.firstOrNull { (cue, _) -> description != null && cue.containsMatchIn(description) }?.value
            ?: EventType.CONCERT.name.takeIf { MUSIC.containsMatchIn("$title\n${description.orEmpty()}") }
            ?: EventType.OTHER.name

    private fun siteTime(property: CalendarProperty): LocalDateTime {
        val raw = property.value.trim()
        if (property.params["VALUE"] == "DATE") return LocalDate.parse(raw, DATE_FORMAT).atStartOfDay()
        val local = LocalDateTime.parse(raw.removeSuffix("Z"), DATE_TIME_FORMAT)
        return if (raw.endsWith('Z')) local.atOffset(ZoneOffset.UTC).atZoneSameInstant(BERLIN).toLocalDateTime() else local
    }

    companion object {
        private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
        private val DATE_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")

        /** The category of the public programme; the groups and courses file under `Alle Gruppen Termine` instead. */
        private const val PROGRAMME_CATEGORY = "Alle Veranstaltungen"

        /** A workshop or a course, named before any `und`: `Swinging TanzT und Swing-Workshop` is a dance night first. */
        private val PARTICIPATION = Regex("""workshop|kurse?\b""", RegexOption.IGNORE_CASE)

        /** A town the society travels to: the programme also lists its trips to venues outside Berlin. */
        private val AWAY = Regex("""\bDelitzsch\b""", RegexOption.IGNORE_CASE)

        private val MARKET = Regex("""markt\b""", RegexOption.IGNORE_CASE)

        /** The house's own words in a title, checked before the shared cues. */
        private val TITLE_FORMATS =
            linkedMapOf(
                MARKET to EventType.OTHER.name,
                Regex("""theater|pantomim""", RegexOption.IGNORE_CASE) to EventType.SHOW.name,
                Regex("""silvester|\btanz(?:t|abend|nacht)\b""", RegexOption.IGNORE_CASE) to EventType.PARTY.name
            )

        /** A format only the description names: a travel slide show is a talk. */
        private val DESCRIPTION_FORMATS =
            linkedMapOf(
                Regex("""multi-?visions?-?show|reisevortrag|diavortrag""", RegexOption.IGNORE_CASE) to EventType.READING.name,
                Regex("""theaterstück|pantomim""", RegexOption.IGNORE_CASE) to EventType.SHOW.name
            )

        private val MUSIC =
            Regex("""musik|konzert|\bband\b|chor|orchest|singen|\bsongs?\b|jazz|folk|klezmer|gitarr""", RegexOption.IGNORE_CASE)

        /** The feed writes a link as ` ( https://… )` after its text; the text alone stays. */
        private val LINK_NOTE = Regex("""\s*\(\s*(?:https?://|tel:|mailto:)[^)]*\)""")

        private val TICKET_LINK = Regex("""Online-Ticket\s*\(\s*(https?://[^\s)]+)\s*\)""")

        /**
         * Whether an entry is programme: filed under [PROGRAMME_CATEGORY], not a workshop or course
         * ([PARTICIPATION], EVENT_SCOPE.md §3.2), and not a trip to a venue elsewhere ([AWAY]).
         */
        fun isProgramme(
            title: String,
            categories: List<String>
        ): Boolean =
            PROGRAMME_CATEGORY in categories &&
                !PARTICIPATION.containsMatchIn(title.substringBefore(" und ")) &&
                !AWAY.containsMatchIn(title)
    }
}
