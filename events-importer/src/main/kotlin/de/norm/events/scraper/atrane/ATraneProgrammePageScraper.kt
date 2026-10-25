package de.norm.events.scraper.atrane

import de.norm.events.common.deshout
import de.norm.events.event.EventType
import de.norm.events.scraper.DOORS_LABELS
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.START_LABELS
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.buildArtistsForEventType
import de.norm.events.scraper.extractEventSlug
import de.norm.events.scraper.htmlParagraphText
import de.norm.events.scraper.isNonArtistName
import de.norm.events.scraper.jsonLdEvents
import de.norm.events.scraper.labelledClock
import de.norm.events.scraper.labelledClockPattern
import de.norm.events.scraper.mapSkippingFailures
import de.norm.events.scraper.parsePriceValue
import de.norm.events.scraper.schemaImageUrl
import de.norm.events.scraper.schemaName
import de.norm.events.scraper.schemaStatus
import de.norm.events.scraper.stringOrNull
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * Pure parser for A-Trane's `/programm/` page, an EventON calendar with one month block per month.
 *
 * Each `.eventon_list_event` card carries a schema.org `Event` JSON-LD block for the name, URL,
 * date, image and blurb, and markup for what the block leaves out: the style tags and the ticket
 * prices. The `startDate` is unpadded with a fixed `+2:00` offset (`2026-11-1T20:00+2:00`), so only
 * its wall-clock digits are read. The name is `<br>`-separated lines under an "A-TRANE PRÄSENTIERT"
 * credit. Featured cards repeat a night, so a night is kept once. Every night is a concert, and
 * its style tags, which the club writes freely, are joined into the genre.
 *
 * The sidemen lines (`Feat: A-B-C`, `WITH: A-B`) bill no artists: they split names on hyphens
 * that double-barrelled names also carry. A weekly session's guests (`HEUTE MIT: A- B`) are
 * billed, because they are split on a hyphen followed by a space only (#2706). A `SPECIAL GUEST:`
 * line names one guest, unsplit, who is billed unless the title already bills them (#3105); a
 * `SPECIAL GUESTS:` line is split on `&` and commas. A `MEETS` line bills the act it names, its quoted
 * name alone when members follow (`MEETS "THE SHAWN & WOLF» Shawn Grocott & Wolfgang Meyer`). A title that only leads in to the act
 * (`WEIHNACHTLICH BESINNLICHES MIT:`) stays the title, and the line below it is the act.
 * An availability banner (`LETZTEN PLÄTZE AN DER BAR`, `AUSVERKAUFT`) can head the lines; it is
 * dropped like the credit (#3165).
 *
 * Some cards print an admission time in their markup: a winter-Sunday subtitle (`EINLASS 17.00 UHR
 * KONZERTBEGINN: 18.00 UHR`), a day-of seats notice (`EINLASS: 19.00 UR`) or a one-night special
 * time (`EINLASS : 21.00 UHR BEGINN : 22.00 UHR`). It is the doors time when it is before the start
 * and the start printed after it, if any, is the night's: a moved night keeps the winter notice (#3870).
 */
class ATraneProgrammePageScraper {
    private val logger = KotlinLogging.logger {}

    fun scrape(document: Document): List<ScrapedEvent> {
        val cards = document.select(".eventon_list_event[data-event_id]")
        logger.info { "Found ${cards.size} event card(s) on the A-Trane programme" }

        return cards
            .mapSkippingFailures(logger, "an A-Trane event card") { card ->
                parseCard(card)
            }.distinctBy { it.sourceId }
    }

    private fun parseCard(card: Element): ScrapedEvent? {
        val event = card.jsonLdEvents().firstOrNull() ?: error("No JSON-LD block found")
        val url = event.stringOrNull("url") ?: error("No event URL found")
        val lines = nameLines(event.schemaName() ?: error("No event name found"))
        if (lines.any { CLOSED.containsMatchIn(it) }) return null
        // A show the club presents in another house is that house's event.
        if (lines.any { ELSEWHERE.containsMatchIn(it) }) return null

        val billing = billing(lines) ?: error("No act found in '${lines.joinToString(" / ")}'")
        val (title, subtitle, guests) = billing
        val act = actName(billing.act)
        val start =
            parseStart(event.stringOrNull("startDate")) ?: return null.also {
                logger.warn { "No parseable start for A-Trane event '$title', skipping" }
            }
        val tags = card.select(".evoet_eventtypes [data-filter^=event_type]").map { it.attr("data-v").trim() }
        return ScrapedEvent(
            title = title,
            subtitle = subtitle,
            description = htmlParagraphText(event.stringOrNull("description")),
            eventType = EventType.CONCERT.name,
            eventDate = start.toLocalDate(),
            startTime = start.toLocalTime(),
            doorsTime = doorsTime(card, start.toLocalTime()),
            imageUrl = event.schemaImageUrl(),
            sourceUrl = url,
            sourceId = "${EventSource.A_TRANE.sourceIdPrefix}${extractEventSlug(url, EVENT_PATH)}",
            genre = tags.filter { it.isNotBlank() && !NOT_A_STYLE.containsMatchIn(it) }.joinToString(", ").ifEmpty { null },
            pricePresale = fullPrice(card),
            free = tags.any { FREE_ENTRY.containsMatchIn(it) },
            status = event.schemaStatus(),
            artists =
                buildArtistsForEventType(act, null, EventType.CONCERT.name) +
                    guests
                        .filterNot { names(act, it) }
                        .distinctBy { it.lowercase() }
                        .map { ScrapedArtist(name = it, role = "SUPPORT") }
        )
    }

    /** The name's lines, entities decoded and non-breaking spaces flattened, blank lines dropped. */
    private fun nameLines(name: String): List<String> =
        name
            .split(LINE_BREAK)
            .map { it.replace(WHITESPACE, " ").trim() }
            .filter { it.isNotEmpty() }

    /** The title, the lines under it, the guests a weekly session or a special-guest line names, and the act. */
    private data class Billing(
        val title: String,
        val subtitle: String?,
        val guests: List<String> = emptyList(),
        val act: String = title
    )

    /**
     * A festival night names its act after "TAG n HEUTE … MIT:" below a banner line, so the
     * lines above that label are dropped. A weekly session names its act above a plain "HEUTE
     * MIT:" label, and the label names the night's guests, or nothing and is dropped.
     */
    private fun billing(lines: List<String>): Billing? {
        val body = lines.filterNot { PRESENTER.containsMatchIn(it) || BANNER.containsMatchIn(it) }
        val acts = body.map { it.replace(TONIGHT_WITH, "").trim() }
        val actIndex = body.indexOfFirst { TONIGHT_WITH.containsMatchIn(it) && it.replace(TONIGHT_WITH, "").isNotBlank() }
        val billing =
            if (actIndex > 0 && GUESTS_LABEL.containsMatchIn(body[actIndex])) {
                withGuests(acts, actIndex)
            } else {
                val rest = acts.drop(actIndex.coerceAtLeast(0)).filter { it.isNotEmpty() }
                rest.firstOrNull()?.let { title ->
                    val act = rest.getOrNull(1)?.takeIf { LEAD_IN.containsMatchIn(title) } ?: title
                    Billing(title, rest.drop(1).joinToString(" · ").ifEmpty { null }, act = act)
                }
            }
        return billing?.let { it.copy(guests = it.guests + body.flatMap(::lineGuests)) }
    }

    /**
     * The guests a line names: one unsplit or several from a `SPECIAL GUEST(S):` label, or the act a `MEETS` line
     * names, its quoted name if it has one. The line stays in the subtitle.
     */
    private fun lineGuests(line: String): List<String> {
        val special = SPECIAL_GUEST.matchEntire(line)
        val met = MEETS.matchEntire(line)?.groupValues?.get(1)
        val names =
            when {
                special == null && met != null -> listOf(QUOTED_NAME.find(met)?.groupValues?.get(1) ?: met)
                special == null -> emptyList()
                special.groupValues[1].isEmpty() -> listOf(special.groupValues[2])
                else -> special.groupValues[2].split(GUESTS_SEPARATOR)
            }
        return names.map { it.trim() }.filter { it.isNotEmpty() && !isNonArtistName(it) }
    }

    /** A weekly session: the act heads the lines, and the label's line names the guests. */
    private fun withGuests(
        acts: List<String>,
        guestIndex: Int
    ): Billing {
        val guests = acts[guestIndex].split(GUEST_SEPARATOR).map(::deshout).filter { it.isNotEmpty() && !isNonArtistName(it) }
        val subtitle = acts.take(guestIndex).drop(1) + "Heute mit: ${guests.joinToString(", ")}" + acts.drop(guestIndex + 1)
        return Billing(acts.first(), subtitle.filter { it.isNotEmpty() }.joinToString(" · "), guests)
    }

    /** Whether [act] already names [guest] as whole words, as `Tony Hurdle Invites TC THE 3RD` names `TC THE 3RD`. */
    private fun names(
        act: String,
        guest: String
    ): Boolean = Regex("""(?<![\p{L}\d])${Regex.escape(guest)}(?![\p{L}\d])""", RegexOption.IGNORE_CASE).containsMatchIn(act)

    /**
     * The act without the album or programme title it names in quotes, or the club's own name a homecoming adds
     * (`JC DOOK BACK AT A-TRANE`), and without a residency label (`ARTIST IN RESIDENCE Jeremy Sassoon`). A slash with
     * a space on one side only (`Esther Kaiser/ Alexander Wienand`) is spaced, so the co-bill splits. An act that is
     * only a quoted name loses the quotes.
     */
    private fun actName(title: String): String =
        title
            .replace(WORK_TITLE, "")
            .replace(VENUE_TAIL, "")
            .replace(RESIDENCY_LABEL, "")
            .replace(LOPSIDED_SLASH, " / ")
            .trim()
            .ifEmpty { title.trim { it in QUOTES } }

    /** The wall-clock start of an unpadded `startDate`; the offset is ignored. */
    private fun parseStart(value: String?): LocalDateTime? =
        START.find(value.orEmpty())?.let {
            try {
                LocalDateTime.parse(it.value, START_FORMAT)
            } catch (_: DateTimeParseException) {
                null
            }
        }

    /** The admission time the card prints, unless it is not before [start] or the notice names another start. */
    private fun doorsTime(
        card: Element,
        start: LocalTime
    ): LocalTime? {
        val text = card.text()
        val doors = DOORS.find(text) ?: return null
        val printedStart = labelledClock(text.substring(doors.range.last + 1), START_LABELS)
        return labelledClock(doors.value, DOORS_LABELS)?.takeIf { it < start && (printedStart == null || printedStart == start) }
    }

    /** The cheapest ticket that is not a student rate; a night without the shop's price list has none. */
    private fun fullPrice(card: Element) =
        card
            .select(".evovo_price_option label")
            .filterNot { CONCESSION.containsMatchIn(it.ownText()) }
            .mapNotNull { parsePriceValue(it.selectFirst(".value")?.text()) }
            .minOrNull()

    private companion object {
        const val EVENT_PATH = "/Events-Directory/"

        val LINE_BREAK = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE)
        val WHITESPACE = Regex("""[\s\u00A0]+""")
        const val QUOTES = "«»\" "
        val WORK_TITLE = Regex("""\s*[«"][^»"]*[»"]\s*$""")
        val VENUE_TAIL = Regex("""\s+(?:(?:back|live)\s+)?(?:at|im)\s+(?:the\s+)?a-?trane\s*$""", RegexOption.IGNORE_CASE)
        val RESIDENCY_LABEL = Regex("""^\s*artist\s+in\s+residence\s*:?\s+""", RegexOption.IGNORE_CASE)
        val LOPSIDED_SLASH = Regex("""(?<=\S)/\s+|\s+/(?=\S)""")
        val START = Regex("""^\d{4}-\d{1,2}-\d{1,2}T\d{1,2}:\d{2}""")
        val START_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("y-M-d'T'H:mm")
        val DOORS = labelledClockPattern(DOORS_LABELS)

        val CLOSED = Regex("""geschlossen|closed""", RegexOption.IGNORE_CASE)
        val ELSEWHERE = Regex("""(?:präsentiert|presents)\s+(?:im|in|at)\s+(?!a-trane)""", RegexOption.IGNORE_CASE)
        val PRESENTER = Regex("""^a-trane\s+(?:präsentiert|presents)""", RegexOption.IGNORE_CASE)
        val BANNER = Regex("""^letzten?\s+pl(?:ä|ae)tze\b|last\s+seats\b|ausverkauft|sold\s*out""", RegexOption.IGNORE_CASE)
        val TONIGHT_WITH = Regex("""^(?:tag\s*\d+\s+)?heute\b.*?\bmit:""", RegexOption.IGNORE_CASE)
        val GUESTS_LABEL = Regex("""^heute\s+mit:""", RegexOption.IGNORE_CASE)
        val SPECIAL_GUEST = Regex("""^(?:featuring\s+)?special\s+guest(s?)\s*:\s*(.+)$""", RegexOption.IGNORE_CASE)
        val MEETS = Regex("""^meets\s+(.+)$""", RegexOption.IGNORE_CASE)
        val QUOTED_NAME = Regex("""^[«"„“]([^»"“”]+)[»"“”]""")
        val GUESTS_SEPARATOR = Regex("""\s*[&,]\s*""")
        val LEAD_IN = Regex("""\bmit:$""", RegexOption.IGNORE_CASE)
        val GUEST_SEPARATOR = Regex("""\s*[-–]\s+""")

        val NOT_A_STYLE = Regex("""live concert|eintritt frei""", RegexOption.IGNORE_CASE)
        val FREE_ENTRY = Regex("""eintritt frei|no cover""", RegexOption.IGNORE_CASE)
        val CONCESSION = Regex("""student""", RegexOption.IGNORE_CASE)
    }
}
