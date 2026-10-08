package de.norm.events.scraper.slaughterhouse

import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.buildArtistsForEventType
import de.norm.events.scraper.cleanEventTitle
import de.norm.events.scraper.isNonArtistName
import de.norm.events.scraper.parseGermanDate
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
 * `Eintritt:` price follow, and a party adds a `: minimal : synth : wave :` style line and a `djs:` line.
 *
 * No entry has its own page, so the source id is the date. A ticket link the editor pasted through Facebook's
 * `l.php` redirect is unwrapped to its target.
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
        val title = cleanEventTitle(rawTitle)
        val type = if (kind.contains("konzert", ignoreCase = true)) EventType.CONCERT.name else EventType.PARTY.name
        val lines = blocks.filter { it.tagName() == "p" && it !== header }.flatMap { it.textLines() }
        val ticketLink =
            blocks.flatMap { it.select("a[href]") }.firstOrNull { link ->
                link
                    .parent()
                    ?.textLines()
                    ?.firstOrNull { link.text() in it }
                    ?.let { TICKETS.containsMatchIn(it) } == true
            }
        val entry = lines.firstOrNull { it.startsWith("Eintritt:") }
        val djs = lines.firstOrNull { it.startsWith("djs:", ignoreCase = true) }?.substringAfter(':')
        return ScrapedEvent(
            title = title,
            description = lines.filterNot(::isFieldLine).joinToString("\n").ifBlank { null },
            eventType = type,
            eventDate = date,
            doorsTime = timeAfter(lines, "Doors"),
            startTime = timeAfter(lines, "Beginn"),
            imageUrl =
                blocks
                    .firstNotNullOfOrNull { it.selectFirst("figure img") }
                    ?.absUrl("src")
                    ?.replace(SIZE_SUFFIX, "")
                    ?.replace(OWN_HTTP, "https://"),
            sourceUrl = baseUrl,
            sourceId = "${EventSource.SLAUGHTERHOUSE.sourceIdPrefix}$date",
            ticketUrl = ticketLink?.absUrl("href")?.let(::unwrapFacebookRedirect),
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
            priceBoxOffice = entry?.let(::parsePriceValue),
            artists = actsOf(title, type, djs)
        )
    }

    private fun timeAfter(
        lines: List<String>,
        label: String
    ): java.time.LocalTime? =
        lines.firstOrNull { it.startsWith("$label:", ignoreCase = true) }?.let { line ->
            TIME.find(line.substringAfter(':'))?.let { parseTime("${it.groupValues[1]}:${it.groupValues[2].ifEmpty { "00" }}") }
        }

    private fun actsOf(
        title: String,
        type: String,
        djs: String?
    ): List<ScrapedArtist> =
        if (type == EventType.PARTY.name) {
            djs
                ?.split(',')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() && !isNonArtistName(it) }
                ?.map { ScrapedArtist(name = it, role = "DJ") }
                .orEmpty()
        } else {
            ACT_PREFIX.find(title)?.let { listOf(ScrapedArtist(name = it.groupValues[1].trim(), role = "HEADLINER", titleDerived = true)) }
                ?: buildArtistsForEventType(title, subtitle = null, eventType = type)
        }

    private fun isFieldLine(line: String): Boolean = FIELD_LINE.containsMatchIn(line) || STYLE_LINE.matches(line) || DIVIDER.matches(line)

    private fun unwrapFacebookRedirect(url: String): String {
        val uri = runCatching { URI(url) }.getOrNull()?.takeIf { it.host?.endsWith("facebook.com") == true && it.path == "/l.php" }
        val target =
            uri
                ?.rawQuery
                ?.split('&')
                ?.firstOrNull { it.startsWith("u=") }
                ?.substringAfter('=')
        return target?.let { URLDecoder.decode(it, StandardCharsets.UTF_8).replace(FBCLID, "").removeSuffix("?") } ?: url
    }

    private companion object {
        val HEADING = Regex("""^((?:Konzert|Party)(?:\s*\+\s*(?:Konzert|Party))?)\s*:\s*(.+)$""", RegexOption.IGNORE_CASE)
        val TICKETS = Regex("""^\s*Tickets?\b""", RegexOption.IGNORE_CASE)
        val FIELD_LINE = Regex("""^(?:Beginn|Doors|Einlass|Eintritt|Tickets?|Ticket Link|djs)\s*:""", RegexOption.IGNORE_CASE)
        val STYLE_LINE = Regex("""^:\s*(.+?)\s*:$""")
        val TIME = Regex("""(\d{1,2})(?:[:.](\d{2}))?\s*(?:Uhr)?""")
        val SIZE_SUFFIX = Regex("""-\d+x\d+(?=\.\w+$)""")
        val FBCLID = Regex("""[?&]fbclid=[^&]*""")

        /** `Capper: Rockin And Clubbin – …` names its band before the colon, and the rest is the night's name. */
        val ACT_PREFIX = Regex("""^([^:]+):\s+""")
        val DIVIDER = Regex("""^[–\-\s]+$""")
        val OWN_HTTP = Regex("""^http://(?=slaughterhouse-berlin\.de/)""")
    }
}
