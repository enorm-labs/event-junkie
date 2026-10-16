package de.norm.events.scraper.pfefferberghaus13

import de.norm.events.event.EventType
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.ScrapedField
import de.norm.events.scraper.WHITESPACE
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
 * the printed text as the note. A concert or a festival bills the bands listed under a `… mit:` line, or the
 * `Name – blurb` / `Name: blurb` items listed under any other line ending in `:` (see [blurbedAct]). Without a list, only a
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

    /**
     * The bands under a paragraph ending in `mit:`, as Punk Rocktopus lists them, or else the blurbed acts under the other
     * paragraphs ending in `:`. The Halloween page lists its bands in German and again in English, so each name counts once.
     */
    private fun lineupOf(text: Element): List<String> {
        val intros = text.children().filter { it.tagName() == "p" && it.text().trimEnd().endsWith(":") }
        val plain =
            intros
                .firstOrNull { it.text().trimEnd().endsWith("mit:") }
                ?.nextElementSibling()
                ?.takeIf { it.tagName() == "ul" }
                ?.select("li")
                ?.map { it.text().trim() }
        return plain ?: intros.flatMap(::blurbedActsAfter).distinctBy { it.lowercase() }
    }

    /** The acts in the lists after [intro], up to the first item that is not one; blank paragraphs between the lists are spacers. */
    private fun blurbedActsAfter(intro: Element): List<String> =
        generateSequence(intro.nextElementSibling()) { it.nextElementSibling() }
            .takeWhile { it.tagName() == "ul" || (it.tagName() == "p" && it.text().replace('\u00A0', ' ').isBlank()) }
            .filter { it.tagName() == "ul" }
            .flatMap { it.children() }
            .map(::blurbedAct)
            .takeWhile { it != null }
            .filterNotNull()
            .toList()

    private companion object {
        val RANGE_OR_FROM = Regex("""^ab\s|\d\s*[-–]\s*\d""", RegexOption.IGNORE_CASE)
        val AMOUNT = Regex("""\d+(?:[.,]\d{1,2})?""")
    }
}

/**
 * The type from the title: a premiere, a reading, a debate or a talk is a [EventType.READING] (EVENT_SCOPE.md §2,
 * #2992), and anything else goes to the concert-venue default, which the house's book and politics nights would otherwise join.
 */
internal fun eventTypeOf(title: String): String = if (READING.containsMatchIn(title)) EventType.READING.name else inferConcertVenueType(title)

internal fun isFreeEntry(text: String?): Boolean = text != null && FREE.containsMatchIn(text)

/** The upload itself, where the page shows its `-166x166` thumbnail. */
internal fun fullSizeImage(src: String): String = src.replace(THUMBNAIL_SUFFIX, "")

internal fun actsOf(
    title: String,
    type: String,
    lineup: List<String>
): List<ScrapedArtist> =
    when {
        type in LISTED_LINEUP_TYPES && lineup.isNotEmpty() -> lineup.filterNot(::isNonArtistName).map { ScrapedArtist(name = it, role = "HEADLINER") }
        ACT_FRAME.containsMatchIn(title) -> buildArtistsForEventType(title, subtitle = null, eventType = type, unpackWithFrame = true)
        else -> emptyList()
    }

/**
 * The act an item such as `<strong>Amptray</strong> – eine Band, die …` or `WR51: Schon fünf Jahre …` names, or null for
 * any other item. A short name and a blurb of words keep `Einlass: 19:00` and `Regulär 10€` out.
 */
internal fun blurbedAct(item: Element): String? {
    val text = item.text().replace('\u00A0', ' ').trim()
    val lead =
        item
            .selectFirst("strong")
            ?.text()
            ?.trim()
            ?.takeIf { it.isNotEmpty() && text.startsWith(it) && LEADING_SEPARATOR.containsMatchIn(text.removePrefix(it)) }
    val (name, blurb) =
        if (lead != null) {
            lead to text.removePrefix(lead).replaceFirst(LEADING_SEPARATOR, "")
        } else {
            BLURBED_ITEM.matchEntire(text)?.destructured?.let { (name, blurb) -> name to blurb } ?: return null
        }
    val isAct = name.split(WHITESPACE).size <= MAX_NAME_WORDS && blurb.firstOrNull()?.isLetter() == true && blurb.split(WHITESPACE).size >= MIN_BLURB_WORDS
    return name.takeIf { isAct }
}

private val LISTED_LINEUP_TYPES = setOf(EventType.CONCERT.name, EventType.FESTIVAL.name)
private val LEADING_SEPARATOR = Regex("""^\s*(?:[–-]|:)\s+""")
private val BLURBED_ITEM = Regex("""(\S.*?)(?:\s+[–-]|:)\s+(.+)""")
private const val MAX_NAME_WORDS = 5
private const val MIN_BLURB_WORDS = 4
private val READING = Regex("""premiere|lesung|kontrovers|diskussion|podium|vortrag|gespräch""", RegexOption.IGNORE_CASE)
private val ACT_FRAME = Regex("""\s(?:mit|feat\.?|ft\.|w/)\s""", RegexOption.IGNORE_CASE)
private val FREE = Regex("""freier eintritt|eintritt frei""", RegexOption.IGNORE_CASE)
private val THUMBNAIL_SUFFIX = Regex("""-\d+x\d+(?=\.\w+$)""")
