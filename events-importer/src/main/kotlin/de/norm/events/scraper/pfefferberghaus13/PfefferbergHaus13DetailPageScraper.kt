package de.norm.events.scraper.pfefferberghaus13

import de.norm.events.event.EventType
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.ScrapedField
import de.norm.events.scraper.buildArtistsForEventType
import de.norm.events.scraper.cleanEventTitle
import de.norm.events.scraper.inferConcertVenueType
import de.norm.events.scraper.isNonArtistName
import de.norm.events.scraper.parseGermanMonthAbbreviation
import de.norm.events.scraper.parsePriceValue
import de.norm.events.scraper.parseTime
import de.norm.events.scraper.textAt
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Pure parser for a Pfefferberg Haus 13 event page. The date carries its year, and `ul.event-meta` lists `Einlass:`,
 * `Beginn:` and `Preis:` rows beside the ticket link or a `Freier Eintritt` line. The text follows the list.
 *
 * A price beside a ticket link is the presale, and one beside `Abendkasse` the box office; `ab 17€` and `7-10€` keep
 * the printed text as the note. A concert that lists its bands under a `… mit:` line bills that list. Without one, only a
 * title that frames its acts (`Soul Night mit Montigo Rim & Hiddit`) bills them: the house titles a night by its series
 * (`Resonanzen – internationale Klänge: Rock den Berg`) or its show (`FIREWORK! – Annabell Whitney`) as often.
 */
class PfefferbergHaus13DetailPageScraper {
    private val logger = KotlinLogging.logger {}

    fun scrape(
        document: Document,
        url: String
    ): ScrapedEvent? {
        val text = document.selectFirst("div.event-text")
        val title = text?.textAt("h2.event-title")?.let(::cleanEventTitle)
        val date = document.selectFirst("div.event-cover")?.let(::dateOf)
        if (text == null || title.isNullOrBlank() || date == null) {
            logger.warn { "Pfefferberg Haus 13 event page $url has no title or date" }
            return null
        }
        val meta = text.select("ul.event-meta > li")
        val ticketUrl = text.selectFirst("ul.event-meta .event-tickets a[href]")?.absUrl("href")
        val price = metaValue(meta, "Preis")
        val type = eventTypeOf(title)
        return ScrapedEvent(
            title = title,
            description = descriptionOf(text),
            eventType = type,
            eventDate = date,
            doorsTime = parseTime(metaValue(meta, "Einlass")),
            startTime = parseTime(metaValue(meta, "Beginn")),
            imageUrl = document.selectFirst("div.event-cover img")?.absUrl("src")?.let(::fullSizeImage),
            sourceUrl = url,
            sourceId = sourceIdOf(url),
            ticketUrl = ticketUrl,
            pricePresale = lowestPrice(price).takeIf { ticketUrl != null },
            priceBoxOffice = lowestPrice(price).takeIf { ticketUrl == null },
            priceNote = price?.takeIf { RANGE_OR_FROM.containsMatchIn(it) },
            free = meta.any { isFreeEntry(it.text()) },
            artists = actsOf(title, type, lineupOf(text)),
            detailPageOwns = setOf(ScrapedField.DESCRIPTION, ScrapedField.IMAGE, ScrapedField.START_TIME)
        )
    }

    /** The lower bound of `7-10€`, which [parsePriceValue] alone reads as 10, the amount beside the sign. */
    private fun lowestPrice(price: String?): BigDecimal? =
        price?.let { text -> AMOUNT.findAll(text).mapNotNull { parsePriceValue("${it.value} €") }.minOrNull() }

    private fun dateOf(cover: Element): LocalDate? {
        val day = cover.textAt(".event-single-day")?.toIntOrNull()
        val month = parseGermanMonthAbbreviation(cover.textAt(".event-single-month"))
        val year = cover.textAt(".event-single-year")?.toIntOrNull()
        return if (day == null || month == null || year == null) null else runCatching { LocalDate.of(year, month, day) }.getOrNull()
    }

    /** The value of the `<span>Label:</span>value` row named [label]. */
    private fun metaValue(
        meta: List<Element>,
        label: String
    ): String? =
        meta
            .firstOrNull { it.selectFirst("span")?.text()?.trimEnd(':') == label }
            ?.ownText()
            ?.trim()
            ?.ifBlank { null }

    private fun descriptionOf(text: Element): String? =
        text
            .clone()
            .apply { select("h2.event-title, ul.event-meta").remove() }
            .children()
            .flatMap { block -> if (block.tagName() == "ul") block.select("li").map { it.text() } else listOf(block.text()) }
            .map { it.replace(' ', ' ').trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
            .ifBlank { null }

    /** The bands under a paragraph ending in `mit:`, as Punk Rocktopus lists them. */
    private fun lineupOf(text: Element): List<String> =
        text
            .select("p")
            .firstOrNull { it.text().trimEnd().endsWith("mit:") }
            ?.nextElementSibling()
            ?.takeIf { it.tagName() == "ul" }
            ?.select("li")
            ?.map { it.text().trim() }
            .orEmpty()

    private companion object {
        val RANGE_OR_FROM = Regex("""^ab\s|\d\s*[-–]\s*\d""", RegexOption.IGNORE_CASE)
        val AMOUNT = Regex("""\d+(?:[.,]\d{1,2})?""")
    }
}

/**
 * The type from the title: a premiere or a reading is a [EventType.READING], a debate or a talk is [EventType.OTHER],
 * and anything else goes to the concert-venue default, which the house's book and politics nights would otherwise join.
 */
internal fun eventTypeOf(title: String): String =
    when {
        READING.containsMatchIn(title) -> EventType.READING.name
        TALK.containsMatchIn(title) -> EventType.OTHER.name
        else -> inferConcertVenueType(title)
    }

internal fun isFreeEntry(text: String?): Boolean = text != null && FREE.containsMatchIn(text)

/** The upload itself, where the page shows its `-166x166` thumbnail. */
internal fun fullSizeImage(src: String): String = src.replace(THUMBNAIL_SUFFIX, "")

internal fun actsOf(
    title: String,
    type: String,
    lineup: List<String>
): List<ScrapedArtist> =
    when {
        type == EventType.CONCERT.name && lineup.isNotEmpty() -> lineup.filterNot(::isNonArtistName).map { ScrapedArtist(name = it, role = "HEADLINER") }
        ACT_FRAME.containsMatchIn(title) -> buildArtistsForEventType(title, subtitle = null, eventType = type, unpackWithFrame = true)
        else -> emptyList()
    }

private val READING = Regex("""premiere|lesung""", RegexOption.IGNORE_CASE)
private val TALK = Regex("""kontrovers|diskussion|podium|vortrag|gespräch""", RegexOption.IGNORE_CASE)
private val ACT_FRAME = Regex("""\s(?:mit|feat\.?|ft\.|w/)\s""", RegexOption.IGNORE_CASE)
private val FREE = Regex("""freier eintritt|eintritt frei""", RegexOption.IGNORE_CASE)
private val THUMBNAIL_SUFFIX = Regex("""-\d+x\d+(?=\.\w+$)""")
