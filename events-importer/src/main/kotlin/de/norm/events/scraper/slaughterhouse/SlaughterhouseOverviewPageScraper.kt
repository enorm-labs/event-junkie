package de.norm.events.scraper.slaughterhouse

import de.norm.events.event.ArtistRole
import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.buildArtistsForEventType
import de.norm.events.scraper.cleanEventTitle
import de.norm.events.scraper.hasFreeEntryPhrase
import de.norm.events.scraper.parseGermanDate
import de.norm.events.scraper.parseLabelledPrices
import de.norm.events.scraper.parsePriceValue
import de.norm.events.scraper.parseTime
import de.norm.events.scraper.textLines
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * Pure parser for the Slaughterhouse `/konzerte/` page, one block-editor post whose entries are runs of blocks between
 * `hr.wp-block-separator` rules. An entry opens with `16.10.2026<br><strong>Konzert: Tyske Ludder + To Avoid</strong>`,
 * whose kind is `Konzert`, `Party` or `Konzert+Party`. A `Beginn:` or `Doors:` line, a `Tickets:` link and an
 * `Eintritt:` price follow, and a party adds a `: minimal : synth : wave :` style line and its DJs ([SlaughterhouseLineup]).
 *
 * The time line's colon is optional (`Beginn 22 Uhr`, `doors open 21:00 Uhr`). A newer entry writes the time first, on one
 * line with a dash when it names both: `19:00 Einlass`, `20:00 Einlass – 21:00 Beginn`. The Factory nights name only doors,
 * with `um 22 Uhr Beginn` in the caption, so a `<time> Uhr Beginn` phrase sets the start when no `Beginn` line does.
 *
 * The price line is `Eintritt: 10€`, `VVK 7€ (+Gebühren) / Abendkasse 10€` or `Eintritt frei`. A labelled amount goes to
 * presale or box office, and an unlabelled one is the door price.
 *
 * A title's dash reads two ways. `Blutgott – Blutfest 2026` names the band and its festival, so the band is on the
 * left. `Stoner’s Fall – Sheev + Ghostones + Caosonora` names the night and its `+` co-bill, so the acts are on the right.
 * A colon reads the same way: `Capper: Rockin And Clubbin` bills Capper, but `Santa’s Riot VI: Deshurance – Hardcore (Berlin) + …`
 * names the series and bills the co-bill after the colon, less each act's genre and town.
 *
 * No entry has its own page, so the source id is the date. A ticket link the editor pasted through Facebook's
 * `l.php` redirect is unwrapped to its target. A `Tickets:` link comes first. A newer entry prints a bare shop link
 * under its price line, so without a label the first link to a ticket shop is the ticket link, and that line leaves the
 * description.
 */
class SlaughterhouseOverviewPageScraper {
    private val logger = KotlinLogging.logger {}

    fun scrape(
        document: Document,
        baseUrl: String
    ): List<ScrapedEvent> {
        val content =
            document.selectFirst("div.entry-content") ?: return emptyList<ScrapedEvent>().also { logger.warn { "No entry content on the Slaughterhouse page" } }
        val entries =
            content.children().fold(mutableListOf(mutableListOf<Element>())) { runs, block ->
                if (block.hasClass("wp-block-separator")) runs.add(mutableListOf()) else runs.last().add(block)
                runs
            }
        val events = entries.mapNotNull { entry(it, baseUrl) }
        logger.info { "Found ${events.size} Slaughterhouse entr(ies)" }
        return events
            .groupBy { it.eventDate }
            .values
            .flatMap { sameDay -> sameDay.mapIndexed { index, event -> if (index == 0) event else event.copy(sourceId = "${event.sourceId}-${index + 1}") } }
    }

    private fun entry(
        blocks: List<Element>,
        baseUrl: String
    ): ScrapedEvent? {
        val header = blocks.firstOrNull { it.tagName() == "p" && it.selectFirst("strong") != null }
        val date = parseGermanDate(header?.textLines()?.firstOrNull())
        val heading = header?.selectFirst("strong")?.let { HEADING.find(it.text().trim()) }
        if (header == null || date == null || heading == null) {
            header?.let { logger.warn { "Slaughterhouse entry '${it.text()}' has no date or kind, skipping" } }
            return null
        }
        val (kind, rawTitle) = heading.destructured
        val cleanedTitle = cleanEventTitle(rawTitle)
        val title = cleanedTitle.text
        val type = if (kind.contains("konzert", ignoreCase = true)) EventType.CONCERT.name else EventType.PARTY.name
        val bodyBlocks = blocks.filter { it.tagName() == "p" && it !== header }
        val paragraphs = bodyBlocks.map { it.textLines() }
        val lines = paragraphs.flatten()
        val priceLine = lines.firstOrNull { PRICE_LINE.containsMatchIn(it) }
        val prices = parseLabelledPrices(priceLine)
        return ScrapedEvent(
            title = title,
            description = descriptionOf(bodyBlocks),
            eventType = type,
            eventDate = date,
            doorsTime = labelledTime(lines, DOORS_LINE) ?: labelledTime(lines, TIME_FIRST_DOORS),
            startTime = labelledTime(lines, START_LINE) ?: labelledTime(lines, TIME_FIRST_START) ?: labelledTime(lines, START_PHRASE),
            imageUrl =
                blocks
                    .firstNotNullOfOrNull { it.selectFirst("figure img") }
                    ?.absUrl("src")
                    ?.replace(SIZE_SUFFIX, "")
                    ?.replace(OWN_HTTP, "https://"),
            sourceUrl = baseUrl,
            sourceId = "${EventSource.SLAUGHTERHOUSE.sourceIdPrefix}$date",
            ticketUrl = ticketUrlOf(blocks),
            genre =
                lines
                    .firstNotNullOfOrNull {
                        STYLE_LINE.matchEntire(it)
                    }?.groupValues
                    ?.get(1)
                    ?.split(':')
                    ?.map { it.trim() }
                    ?.filter { it.isNotEmpty() }
                    ?.joinToString(", "),
            pricePresale = prices.presale,
            priceBoxOffice = prices.boxOffice ?: priceLine?.takeIf { prices.presale == null }?.let(::parsePriceValue),
            free = hasFreeEntryPhrase(priceLine),
            soldOut = cleanedTitle.soldOut,
            artists = actsOf(title, type, SlaughterhouseLineup.of(paragraphs))
        )
    }

    private fun labelledTime(
        lines: List<String>,
        pattern: Regex
    ): java.time.LocalTime? =
        lines
            .firstNotNullOfOrNull {
                pattern.find(it)
            }?.let { parseTime("${it.groupValues[1].padStart(2, '0')}:${it.groupValues[2].ifEmpty { "00" }}") }

    /**
     * The acts the entry's lines name ([SlaughterhouseLineup]), and the title's acts only for a concert whose lines name none.
     * A party bills only its DJs.
     */
    private fun actsOf(
        title: String,
        type: String,
        lineup: SlaughterhouseLineup
    ): List<ScrapedArtist> {
        val djs = lineup.djs.map { ScrapedArtist(name = it, role = ArtistRole.DJ) }
        if (type == EventType.PARTY.name) return djs
        val headliners =
            lineup.headliners.map { ScrapedArtist(name = it, role = ArtistRole.HEADLINER) }.ifEmpty {
                seriesCoBill(title)?.let { buildArtistsForEventType(it, subtitle = null, eventType = type) }
                    ?: ACT_PREFIX.find(title)?.let { listOf(ScrapedArtist(name = it.groupValues[1].trim(), role = ArtistRole.HEADLINER, titleDerived = true)) }
                    ?: buildArtistsForEventType(billedSideOfDash(title), subtitle = null, eventType = type)
            }
        return headliners + djs
    }

    private fun seriesCoBill(title: String): String? =
        ACT_PREFIX
            .find(title)
            ?.let { title.substring(it.range.last + 1) }
            ?.takeIf { CO_BILL.containsMatchIn(it) }
            ?.replace(GENRE_TAIL, "")

    private fun billedSideOfDash(title: String): String =
        SERIES_DASH
            .find(title)
            ?.groupValues
            ?.get(1)
            ?.takeIf { CO_BILL.containsMatchIn(it) } ?: title

    /** The body paragraphs less their field lines, a blank line between two, as the page spaces them; `<br><br>` is a gap too. */
    private fun descriptionOf(paragraphs: List<Element>): String? =
        paragraphs
            .map { paragraph ->
                paragraph
                    .textLines(keepBlankLines = true)
                    .filterNot(::isFieldLine)
                    .joinToString("\n")
                    .replace(BLANK_RUN, "\n\n")
                    .trim()
            }.filter { it.isNotEmpty() }
            .joinToString("\n\n")
            .ifEmpty { null }

    /** A field line, or a line that is only a ticket shop's link, which [ticketUrlOf] reads. */
    private fun isFieldLine(line: String): Boolean =
        FIELD_LINE.containsMatchIn(line) || PRICE_LINE.containsMatchIn(line) || STYLE_LINE.matches(line) || DIVIDER.matches(line) ||
            (BARE_LINK.matches(line) && isTicketShop(line.trim().let { if ("://" in it) it else "https://$it" }))

    /** The link on a `Tickets:` line, or else the first link to a ticket shop, each unwrapped from Facebook's redirect. */
    private fun ticketUrlOf(blocks: List<Element>): String? {
        val links = blocks.flatMap { it.select("a[href]") }
        val labelled =
            links.firstOrNull { link ->
                link
                    .parent()
                    ?.textLines()
                    ?.firstOrNull { link.text() in it }
                    ?.let { TICKETS.containsMatchIn(it) } == true
            }
        return labelled?.absUrl("href")?.let(::unwrapFacebookRedirect)
            ?: links.map { unwrapFacebookRedirect(it.absUrl("href")) }.firstOrNull(::isTicketShop)
    }

    private fun isTicketShop(url: String): Boolean {
        val host = runCatching { URI(url).host }.getOrNull()?.lowercase() ?: return false
        return TICKET_SHOPS.any { host == it || host.endsWith(".$it") }
    }

    private fun unwrapFacebookRedirect(url: String): String {
        val uri = runCatching { URI(url) }.getOrNull()?.takeIf { it.host?.endsWith("facebook.com") == true && it.path == "/l.php" }
        val target =
            uri
                ?.rawQuery
                ?.split('&')
                ?.firstOrNull { it.startsWith("u=") }
                ?.substringAfter('=')
        return target?.let { URLDecoder.decode(it, StandardCharsets.UTF_8) } ?: url
    }

    private companion object {
        const val TIME_FIRST = """\d{1,2}(?:[:.]\d{2})?\s*(?:Uhr\s+)?"""
        const val TIME_FIRST_CAPTURE = """(\d{1,2})(?:[:.](\d{2}))?"""
        val BLANK_RUN = Regex("""\n{3,}""")

        /** A line that is one link and nothing else: `https://www.tixforgigs.com/…` or `tixforgigs.com`. */
        val BARE_LINK = Regex("""^\s*(?:https?://)?[\w.-]+\.\p{L}{2,}(?:/\S*)?\s*$""")

        val HEADING = Regex("""^((?:Konzert|Party)(?:\s*\+\s*(?:Konzert|Party))?)\s*:\s*(.+)$""", RegexOption.IGNORE_CASE)
        val TICKETS = Regex("""^\s*Tickets?\b""", RegexOption.IGNORE_CASE)

        /** The shops whose link is a ticket link without a `Tickets:` label; a band's site or Instagram is not. */
        val TICKET_SHOPS = setOf("tixforgigs.com")
        val FIELD_LINE =
            Regex(
                """^(?:(?:Beginn|Doors(?:\s+open)?|Einlass)(?:\s*:|\s+\d)|(?:Eintritt|Tickets?|Ticket Link|djs)\s*:|$TIME_FIRST(?:Einlass|Beginn)\b)""",
                RegexOption.IGNORE_CASE
            )

        /** `Eintritt: 10€`, `Eintritt frei`, `VVK 12 Euro (+Gebühren) / Abendkasse 15 Euro`; a bare `Abendkasse` names no price. */
        val PRICE_LINE = Regex("""^(?:Eintritt\s*:|Eintritt\s+frei\b|(?:VVK|Vorverkauf)\b|Abendkasse\b.*\d)""", RegexOption.IGNORE_CASE)
        val STYLE_LINE = Regex("""^:\s*(.+?)\s*:$""")
        val START_LINE = Regex("""^Beginn(?:\s*:\D*|\s+)(\d{1,2})(?:[:.](\d{2}))?""", RegexOption.IGNORE_CASE)
        val DOORS_LINE = Regex("""^(?:Doors(?:\s+open)?|Einlass)(?:\s*:\D*|\s+)(\d{1,2})(?:[:.](\d{2}))?""", RegexOption.IGNORE_CASE)

        /** `20:00 Einlass – 21:00 Beginn`: a line that opens with a time, then its keyword, and maybe a second pair after a dash. */
        val TIME_FIRST_DOORS = Regex("""^(?:$TIME_FIRST\p{L}+\s*[–—/|-]\s*)?$TIME_FIRST_CAPTURE\s*(?:Uhr\s+)?Einlass\b""", RegexOption.IGNORE_CASE)
        val TIME_FIRST_START = Regex("""^(?:$TIME_FIRST\p{L}+\s*[–—/|-]\s*)?$TIME_FIRST_CAPTURE\s*(?:Uhr\s+)?Beginn\b""", RegexOption.IGNORE_CASE)

        /** The Factory caption's `um 22 Uhr Beginn`: the time before the keyword. */
        val START_PHRASE = Regex("""\b(\d{1,2})(?:[:.](\d{2}))?\s*Uhr\s+Beginn\b""", RegexOption.IGNORE_CASE)
        val SIZE_SUFFIX = Regex("""-\d+x\d+(?=\.\w+$)""")

        /** `Capper: Rockin And Clubbin – …` names its band before the colon, and the rest is the night's name. */
        val ACT_PREFIX = Regex("""^([^:]+):\s+""")

        /** A co-billed act's `– Hardcore (Berlin)`: its genre and home town, not part of its name. */
        val GENRE_TAIL = Regex("""\s+[–—-]\s+[^+–—]+?\s*\([^()+]+\)(?=\s*(?:\+|$))""")
        val SERIES_DASH = Regex("""^.+?\s+[–—-]\s+(.+)$""")
        val CO_BILL = Regex("""\s\+\s""")
        val DIVIDER = Regex("""^[–\-\s]+$""")
        val OWN_HTTP = Regex("""^http://(?=slaughterhouse-berlin\.de/)""")
    }
}
