package de.norm.events.scraper.zku

import de.norm.events.event.EventType
import de.norm.events.scraper.RunningOrderClock
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.blankToNull
import de.norm.events.scraper.buildArtistsForEventType
import de.norm.events.scraper.detectFree
import de.norm.events.scraper.isNonArtistName
import de.norm.events.scraper.parsePriceValue
import de.norm.events.scraper.parseTime
import de.norm.events.scraper.splitBackToBack
import de.norm.events.scraper.textLines
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Reads a ZK/U `/timeline/<slug>/` page into the calendar's row: the blurb, the full poster, the
 * `Line up` block, the `hosted by` credit, the `Entry …` line and a `Tickets` button. Each sits
 * in its own TYPO3 text frame with no field markup, so a frame is found by its heading or its
 * opening words. An entry the calendar left `OTHER` is typed here from the page's words.
 */
class ZkuEventPageScraper {
    fun enrich(
        event: ScrapedEvent,
        document: Document
    ): ScrapedEvent? {
        val article = document.selectFirst("main > article") ?: return null
        val frames = article.select("section .frame")
        val description = description(article)
        val slots = frames.firstNotNullOfOrNull { lineUpLines(it) }.orEmpty().mapNotNull(::parseSlot)
        val eventType = typeFromPage(event, listOfNotNull(description) + slots.map { it.line })
        val priceNote = frames.firstNotNullOfOrNull { paragraphStarting(it, ENTRY_LINE) }
        val priceBoxOffice = parsePriceValue(priceNote)
        return event.copy(
            eventType = eventType,
            typeIsFallback = event.typeIsFallback && eventType == event.eventType,
            description = description,
            imageUrl = article.selectFirst("figure [itemprop=image]")?.absUrl("src").blankToNull() ?: event.imageUrl,
            ticketUrl =
                article
                    .select("a.button")
                    .firstOrNull { it.text().trim().equals("Tickets", ignoreCase = true) }
                    ?.absUrl("href")
                    .blankToNull(),
            priceBoxOffice = priceBoxOffice,
            priceNote = priceNote,
            free = detectFree(priceBoxOffice = priceBoxOffice, priceNote = priceNote),
            artists = artistsOf(event, eventType, slots),
            promoters = listOfNotNull(frames.firstNotNullOfOrNull(::host))
        )
    }

    /** The blurb under the date line, one paragraph per `p`. */
    private fun description(article: Element): String? =
        article
            .select("> div.block > div.block-padding > p:not(.date):not(.location)")
            .mapNotNull { it.textLines().joinToString("\n").blankToNull() }
            .joinToString("\n\n")
            .blankToNull()

    /** The lines of a `Line up` frame's first paragraph; the next one gives floor hours. */
    private fun lineUpLines(frame: Element): List<String>? =
        frame
            .selectFirst("h1")
            ?.takeIf { LINE_UP_HEADING.matches(it.text().trim()) }
            ?.let { frame.selectFirst(".ce-bodytext > p") }
            ?.textLines()

    private fun parseSlot(line: String): Slot? {
        val timed = TIMED_SLOT.find(line)
        val billing = (timed?.let { line.substring(it.range.last + 1) } ?: line).trim()
        val name =
            billing
                .replace(DJ_SUFFIX, "")
                .trim()
                .blankToNull()
                ?.takeUnless { isNonArtistName(it) || SECRET_SLOT.containsMatchIn(it) } ?: return null
        return Slot(line, name, isDj = DJ_SUFFIX.containsMatchIn(billing), start = timed?.groupValues?.get(1), end = timed?.groupValues?.get(2))
    }

    /** The calendar's type, or for an `OTHER` entry a club night or a concert named on the page (#2992). */
    private fun typeFromPage(
        event: ScrapedEvent,
        texts: List<String>
    ): String? {
        if (event.eventType != EventType.OTHER.name) return event.eventType
        val text = texts.joinToString("\n")
        return when {
            CLUB_NIGHT_WORDS.containsMatchIn(text) -> EventType.PARTY.name
            CONCERT_WORDS.containsMatchIn(text) -> EventType.CONCERT.name
            else -> event.eventType
        }
    }

    private fun artistsOf(
        event: ScrapedEvent,
        eventType: String?,
        slots: List<Slot>
    ): List<ScrapedArtist> {
        if (slots.isEmpty()) {
            return if (eventType == EventType.CONCERT.name) buildArtistsForEventType(event.title, event.subtitle, eventType) else event.artists
        }
        val clock = RunningOrderClock(event.eventDate)
        return slots.flatMap { slot ->
            val role = if (slot.isDj || eventType == EventType.PARTY.name) "DJ" else "HEADLINER"
            val times = slot.start?.let { start -> clockAt(start)?.let { clock.slot(it, slot.end?.let(::clockAt)) } }
            splitBackToBack(ScrapedArtist(name = slot.name, role = role, setStart = times?.first, setEnd = times?.second))
        }
    }

    /** A set time, with the venue's `24:00` read as midnight. */
    private fun clockAt(text: String) = parseTime(text.padStart(CLOCK_LENGTH, '0').replace("24:", "00:"))

    /** Who the page says hosts the night: the linked name, or the words after `hosted by`. */
    private fun host(frame: Element): String? {
        val paragraph = frame.select("p").firstOrNull { HOSTED_BY.containsMatchIn(it.text()) } ?: return null
        val linked = paragraph.selectFirst("a")?.text().blankToNull()
        return linked ?: HOSTED_BY
            .find(paragraph.text())
            ?.groupValues
            ?.get(1)
            ?.trimEnd('.')
            ?.blankToNull()
    }

    private fun paragraphStarting(
        frame: Element,
        opening: Regex
    ): String? = frame.select("p").map { it.text().trim() }.firstOrNull { opening.containsMatchIn(it) }

    private data class Slot(
        val line: String,
        val name: String,
        val isDj: Boolean,
        val start: String?,
        val end: String?
    )

    private companion object {
        val LINE_UP_HEADING = Regex("""line[\s-]?up""", RegexOption.IGNORE_CASE)
        val TIMED_SLOT = Regex("""^\s*(\d{1,2}:\d{2})\s*[–-]\s*(\d{1,2}:\d{2})""")
        val DJ_SUFFIX = Regex("""\s+[–-]\s*DJ\s*$""", RegexOption.IGNORE_CASE)

        /** An unannounced slot: `Secret Live Act`. */
        val SECRET_SLOT = Regex("""^secret\b""", RegexOption.IGNORE_CASE)
        val ENTRY_LINE = Regex("""^Entry\b""", RegexOption.IGNORE_CASE)
        val HOSTED_BY = Regex("""hosted by\s+(?:the\s+)?(.+)""", RegexOption.IGNORE_CASE)
        val CLUB_NIGHT_WORDS = Regex("""\bDJs?\b|dance\s?floor""", RegexOption.IGNORE_CASE)
        val CONCERT_WORDS = Regex("""\bconcerts?\b|\bbands?\b|live music""", RegexOption.IGNORE_CASE)
        const val CLOCK_LENGTH = 5
    }
}
