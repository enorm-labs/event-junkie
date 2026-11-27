package de.norm.events.scraper.labsaal

import de.norm.events.event.EventType
import de.norm.events.scraper.CalendarProperty
import de.norm.events.scraper.DOORS_LABELS
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.blankToNull
import de.norm.events.scraper.calendarEvents
import de.norm.events.scraper.childrensShowPhrase
import de.norm.events.scraper.cleanEventTitle
import de.norm.events.scraper.decodeHtmlEntities
import de.norm.events.scraper.extractEventSlug
import de.norm.events.scraper.forChildrenIf
import de.norm.events.scraper.hasFreeEntryPhrase
import de.norm.events.scraper.htmlParagraphText
import de.norm.events.scraper.inferUnmarkedTitleType
import de.norm.events.scraper.isNonArtistName
import de.norm.events.scraper.labelledClock
import de.norm.events.scraper.orderDoorsBeforeStart
import de.norm.events.scraper.parseLabelledPrices
import de.norm.events.scraper.parseListing
import de.norm.events.scraper.siteTime
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Pure parser for the Modern Events Calendar iCalendar feed of the LabSaal. The feed also carries
 * the society's weekly groups and courses; only the `Alle Veranstaltungen` category is the
 * programme ([isProgramme]). The site names no format, so the type comes from the title, then from
 * a format the description names, then from a music cue; a night with none is typed other.
 * A concert title bills the act after `mit`, or before `erstmals im LabSaal` ([concertActs]).
 * A title such as `Theater für Kinder: …` marks the night for-children ([childrensShowPhrase],
 * EVENT_SCOPE.md §3.5). Only the title is read: it marks every children's show, and the
 * description adds none.
 */
class LabsaalCalendarScraper {
    private val logger = KotlinLogging.logger {}

    fun scrape(calendar: String): List<ScrapedEvent> {
        val entries = calendarEvents(calendar)
        return entries.parseListing(logger, "LabSaal calendar entry") { parseEntry(it) }
    }

    private fun parseEntry(entry: Map<String, CalendarProperty>): ScrapedEvent? {
        val cleanedTitle =
            entry["SUMMARY"]
                ?.text
                ?.let(::decodeHtmlEntities)
                ?.let(::cleanEventTitle)
        val title = cleanedTitle?.text.blankToNull()
        val url = entry["URL"]?.value.blankToNull()
        val start = entry["DTSTART"]?.siteTime()?.dateTime
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
        val slug = extractEventSlug(url)
        if (!isProgramme(title, categories, slug)) return null
        val rawDescription = entry["DESCRIPTION"]?.text
        val description = htmlParagraphText(rawDescription?.replace(LINK_NOTE, ""))
        val (doors, startTime) = orderDoorsBeforeStart(labelledClock(description, DOORS_LABELS), start.toLocalTime())
        val prices = parseLabelledPrices(description)
        val hasPrice = prices.presale != null || prices.boxOffice != null
        val eventType = eventType(title, description)
        return ScrapedEvent(
            title = title,
            soldOut = cleanedTitle?.soldOut == true,
            description = description,
            eventType = eventType,
            typeIsFallback = eventType == EventType.OTHER.name && MARKET.containsMatchIn(title).not(),
            artists = if (eventType == EventType.CONCERT.name) concertActs(title) else emptyList(),
            eventDate = start.toLocalDate(),
            doorsTime = doors,
            startTime = startTime?.takeUnless { it == doors },
            imageUrl = entry["ATTACH"]?.takeIf { it.params["FMTTYPE"]?.startsWith("image/") == true }?.value,
            sourceUrl = url,
            sourceId = "${EventSource.LABSAAL.sourceIdPrefix}$slug",
            ticketUrl = rawDescription?.let { TICKET_LINK.find(it)?.groupValues?.get(1) },
            pricePresale = prices.presale,
            priceBoxOffice = prices.boxOffice,
            free = !hasPrice && hasFreeEntryPhrase(description)
        ).forChildrenIf(childrensShowPhrase(title, null))
    }

    /**
     * The act of `Tanz in den Mai mit BLACKMAIL Berlin`, `Bigband Kameleon erstmals im LabSaal` or `Von Kishinev nach New York – Wedding
     * Klezmer`; a `Kooperation mit` names a partner.
     */
    private fun concertActs(title: String): List<ScrapedArtist> {
        val act =
            FIRST_TIME.find(title)?.groupValues?.get(1) ?: BILLED_WITH.find(title)?.groupValues?.get(1)
                ?: DASHED_ACT.find(title)?.groupValues?.get(1)
        return listOfNotNull(act?.takeUnless(::isNonArtistName)?.let { ScrapedArtist(name = it) })
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

    companion object {
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
                Regex("""disco\b|silvester|\btanz(?:t|abend|nacht)\b""", RegexOption.IGNORE_CASE) to EventType.PARTY.name
            )

        /** `Herbstkonzert mit dem Shanty Chor Reinickendorf`: the act opens with a capital. */
        private val BILLED_WITH = Regex("""^(?!(?i:kooperation)\b).+?\smit\s+(?:(?:dem|den|der)\s+)?(\p{Lu}.*)$""")

        /** A run of up to four capitalised words after a spaced dash is the band (event 15710); a lowercase word is prose. */
        private val DASHED_ACT = Regex("""^.+?\s[–-]\s+(\p{Lu}[\p{L}\p{N}'’.&-]*(?:\s+\p{Lu}[\p{L}\p{N}'’.&-]*){0,3})$""")

        private val FIRST_TIME = Regex("""^(.+?)\s+erstmals\s+im\s+LabSaal$""", RegexOption.IGNORE_CASE)

        /** A format only the description names: a travel slide show is a talk, the Brauseboys' Leseshow a reading, and `die Ausstellung … anzusehen` a run. */
        private val DESCRIPTION_FORMATS =
            linkedMapOf(
                Regex("""multi-?visions?-?show|reisevortrag|diavortrag|leseshow|lesebühne""", RegexOption.IGNORE_CASE) to EventType.READING.name,
                Regex("""theaterstück|pantomim""", RegexOption.IGNORE_CASE) to EventType.SHOW.name,
                Regex("""\bdie\s+ausstellung\b[^.!?]*\b(?:anzusehen|anzuschauen|zu\s+sehen|zu\s+besichtigen)\b""", RegexOption.IGNORE_CASE) to
                    EventType.EXHIBITION.name
            )

        private val MUSIC =
            Regex("""musik|konzert|\bband\b|chor|orchest|singen|\bsongs?\b|jazz|folk|klezmer|gitarr""", RegexOption.IGNORE_CASE)

        /** The feed writes a link as ` ( https://… )` after its text; the text alone stays. */
        private val LINK_NOTE = Regex("""\s*\(\s*(?:https?://|tel:|mailto:)[^)]*\)""")

        private val TICKET_LINK = Regex("""Online-Ticket\s*\(\s*(https?://[^\s)]+)\s*\)""")

        /**
         * Whether an entry is programme: filed under [PROGRAMME_CATEGORY], not a workshop or course
         * ([PARTICIPATION], EVENT_SCOPE.md §3.2), and not a trip to a venue elsewhere ([AWAY]). The page's
         * [slug] keeps the title the entry was first published with, so it can name a workshop the title
         * has lost: `kindertanzen-im-labsaal-workshop-in-den-herbsferien-468` (event 12520).
         */
        fun isProgramme(
            title: String,
            categories: List<String>,
            slug: String? = null
        ): Boolean =
            PROGRAMME_CATEGORY in categories &&
                listOfNotNull(title, slug?.replace('-', ' ')).none { PARTICIPATION.containsMatchIn(it.substringBefore(" und ")) } &&
                !AWAY.containsMatchIn(title)
    }
}
