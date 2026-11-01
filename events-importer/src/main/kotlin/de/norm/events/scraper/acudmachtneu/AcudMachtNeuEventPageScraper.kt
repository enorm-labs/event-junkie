package de.norm.events.scraper.acudmachtneu

import de.norm.events.scraper.DOORS_LABELS
import de.norm.events.scraper.START_LABELS
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.UNRESOLVED_EVENT_DATE
import de.norm.events.scraper.blankToNull
import de.norm.events.scraper.euroAmounts
import de.norm.events.scraper.htmlParagraphText
import de.norm.events.scraper.isNonArtistName
import de.norm.events.scraper.labelledClock
import de.norm.events.scraper.parseClock
import de.norm.events.scraper.splitBackToBack
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import java.time.LocalTime

/**
 * Pure parser for one ACUD event page. The header line names the room and one time, `ACUD CLUB 20h → Concert`. The
 * text below repeats the facts in free form: a labelled `Start:` there wins over the header's time, which is sometimes
 * the doors time. `Doors:`, `Entrance:`, `Tickets: 16E` and `FREE ENTRY` lines, a ticket link after `Presale:` or `Ticketing`, and a `Line up`
 * paragraph are read where present. The paragraph names one act per line, or a festival's acts on one line between
 * slashes (`ABIGAIL TOLL / ACTIVEMIRROR /CALVIN LOVE`).
 */
class AcudMachtNeuEventPageScraper {
    fun scrape(
        document: Document,
        sourceUrl: String
    ): ScrapedEvent? {
        val article = document.selectFirst("article.event-article") ?: return null
        val title = article.selectFirst("h1.t-title-header--event")?.text().blankToNull() ?: return null
        val header =
            HEADER.find(
                article
                    .selectFirst(".t-margin-header--t2")
                    ?.text()
                    ?.replace(' ', ' ')
                    .orEmpty()
            )
        val content = article.selectFirst(".event-article__content")
        val text = content?.let { htmlParagraphText(it.html()) }
        val lines = text?.lines().orEmpty()
        val startTime = lines.firstNotNullOfOrNull { labelledClock(it, START_LABELS) } ?: header?.let(::headerTime)
        val entrance = lines.firstNotNullOfOrNull(::labelledPrice) ?: lines.firstOrNull { FREE_ENTRY.containsMatchIn(it) }?.trim()
        val doorPrice = euroAmounts(entrance).singleOrNull()?.takeUnless { RANGE.containsMatchIn(entrance.orEmpty()) }
        return ScrapedEvent(
            title = title,
            description = text,
            eventDate = UNRESOLVED_EVENT_DATE,
            startTime = startTime,
            doorsTime = lines.firstNotNullOfOrNull { labelledClock(it, DOORS_LABELS) }?.takeIf { it != startTime },
            room = header?.groupValues?.get(1)?.let(::roomName),
            imageUrl = article.selectFirst(".event-article__figure img[src]")?.absUrl("src").blankToNull(),
            ticketUrl = content?.let(::ticketUrl),
            priceBoxOffice = doorPrice,
            priceNote = entrance?.takeIf { doorPrice == null },
            sourceUrl = sourceUrl,
            sourceId = acudMachtNeuSourceId(sourceUrl),
            artists = content?.let(::lineUp).orEmpty()
        )
    }

    /**
     * The text after `Entrance:` or `Entrance fee:`, or after `Tickets:` or `Price at the door` when it holds an amount, since
     * `Tickets:` also heads a shop link, or the amount before `at the door` (`28e at the door`). `16E` and `28e` are the
     * venue's spellings of `16 €` and `28 €`.
     */
    private fun labelledPrice(line: String): String? {
        val text = line.replace(LETTER_E_EURO, "$1 €")
        return ENTRANCE
            .find(text)
            ?.groupValues
            ?.get(1)
            .blankToNull()
            ?: DOOR_PRICE
                .find(text)
                ?.groupValues
                ?.get(1)
                ?.takeIf { euroAmounts(it).isNotEmpty() }
                ?.trim()
            ?: AMOUNT_AT_THE_DOOR
                .find(text)
                ?.groupValues
                ?.get(1)
                ?.takeIf { euroAmounts(it).isNotEmpty() }
    }

    private fun headerTime(match: MatchResult): LocalTime? {
        val (_, hour, minute) = match.destructured
        return parseClock("$hour:${minute.ifEmpty { "00" }}")
    }

    /** `ACUD CLUB` is the Club, `ACUD GALERIE` the Galerie, as the site's Spaces menu names them. */
    private fun roomName(raw: String): String? =
        raw
            .replace(ACUD_PREFIX, "")
            .blankToNull()
            ?.lowercase()
            ?.replaceFirstChar { it.uppercase() }

    /** The first link whose label just before it names a presale or tickets: `Presale: <a>`, `Ticketing<br><a>`. */
    private fun ticketUrl(content: Element): String? =
        content
            .select("a[href]")
            .firstOrNull { link -> textBefore(link)?.let { TICKET_LABEL.containsMatchIn(it) } == true }
            ?.absUrl("href")
            .blankToNull()

    private fun textBefore(link: Element): String? {
        var node: Node? = link.previousSibling()
        while (node != null && node.isSpacing()) node = node.previousSibling()
        return when (node) {
            is TextNode -> node.text()
            is Element -> node.text()
            else -> null
        }
    }

    /** Blank text or a line break between a label and its link. */
    private fun Node.isSpacing(): Boolean = (this is TextNode && isBlank) || (this is Element && normalName() == "br")

    /** The acts of the paragraph headed `Line up` or `LINE UP`; `Name – Track` bills the name. */
    private fun lineUp(content: Element): List<ScrapedArtist> {
        val paragraph = content.select("p").firstOrNull { LINE_UP.containsMatchIn(it.text()) } ?: return emptyList()
        return htmlParagraphText(paragraph.html())
            .orEmpty()
            .lines()
            .dropWhile { !LINE_UP.containsMatchIn(it) }
            .map { it.replace(LINE_UP, "") }
            .flatMap { it.split(SLASH_SEPARATOR) }
            .map { it.split(TRACK_TAIL, limit = 2).first() }
            .flatMap(::splitBackToBack)
            .mapNotNull { it.blankToNull() }
            .filterNot(::isNonArtistName)
            .map { ScrapedArtist(name = it) }
    }

    private companion object {
        /** `ACUD CLUB 20h → …`: the room, then the hour. */
        val HEADER = Regex("""^\s*(.*?)\s*(\d{1,2})(?:[:.](\d{2}))?\s*h\b""", RegexOption.IGNORE_CASE)

        val ACUD_PREFIX = Regex("""^\s*acud\s*""", RegexOption.IGNORE_CASE)

        val ENTRANCE = Regex("""^\s*(?:entrance(?:\s+fee)?|eintritt)\s*:\s*(.*)$""", RegexOption.IGNORE_CASE)

        val DOOR_PRICE = Regex("""^\s*(?:tickets?\s*:|price at the door(?:\s+(?:will be|is))?\s*:?)\s*(.*)$""", RegexOption.IGNORE_CASE)

        /** An amount, or a sliding scale, just before `at the door`. */
        val AMOUNT_AT_THE_DOOR =
            Regex("""((?:\d+(?:[.,]\d{1,2})?\s*[-–]\s*)?(?:€\s*)?\d+(?:[.,]\d{1,2})?\s*€?)\s+at the door\b""", RegexOption.IGNORE_CASE)

        /** `16E` or `28e`, but not the `E` that starts a word (`3 Ebenen`, `2026 E-Werk`). */
        val LETTER_E_EURO = Regex("""(\d)\s?[eE](?![\p{L}\d-])""")

        val FREE_ENTRY = Regex("""^\s*(?:free entry|free admission|eintritt frei)\b""", RegexOption.IGNORE_CASE)

        /** `15 - 18 €` is a sliding scale, kept as the venue wrote it. */
        val RANGE = Regex("""\d\s*[-–]\s*\d""")

        val TICKET_LABEL = Regex("""(?:presale|vorverkauf|vvk|tickets?|ticketing)\s*:?\s*$""", RegexOption.IGNORE_CASE)

        val LINE_UP = Regex("""^\s*line[\s-]?up\s*:?\s*""", RegexOption.IGNORE_CASE)

        val TRACK_TAIL = Regex("""\s+[–—-]\s+""")

        /** A slash with a space on at least one side; a bare one stays inside a name (`AC/DC`). */
        val SLASH_SEPARATOR = Regex("""\s+/\s*|/\s+""")
    }
}
