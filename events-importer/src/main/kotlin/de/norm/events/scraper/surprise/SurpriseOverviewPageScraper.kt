package de.norm.events.scraper.surprise

import de.norm.events.event.ArtistRole
import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.attrAt
import de.norm.events.scraper.blankToNull
import de.norm.events.scraper.collapseWhitespace
import de.norm.events.scraper.euroAmounts
import de.norm.events.scraper.mapSkippingFailures
import de.norm.events.scraper.textAt
import de.norm.events.scraper.textLines
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

/**
 * Pure parser for Surprise Club's hand-built `events-programs.php`. Each night is a
 * `div.every-event-displayed`: a title with a trailing dash, the date split into day, English month
 * and year, a door price for ladies and one for gentlemen, a text, a dot-separated style line and a
 * `-- Dj A • Dj B --` line. The ladies' price is the box-office price; both go in the price note.
 */
class SurpriseOverviewPageScraper {
    private val logger = KotlinLogging.logger {}

    fun scrape(
        document: Document,
        sourceUrl: String
    ): List<ScrapedEvent> {
        val rows = document.select("div.every-event-displayed")
        logger.info { "Found ${rows.size} night(s) on the Surprise Club events page" }
        return rows.mapSkippingFailures(logger, "a Surprise Club night") { parseNight(it, sourceUrl) }
    }

    private fun parseNight(
        row: Element,
        sourceUrl: String
    ): ScrapedEvent? {
        val title = row.textAt(".events-date-displayed h2")?.trimEnd('-', ' ', '–')?.blankToNull()
        val date = parseDate(row)
        if (title == null || date == null) {
            logger.warn { "Surprise Club night without a title or a date ('$title', $date), skipping" }
            return null
        }
        val doorPrices = doorPrices(row)
        return ScrapedEvent(
            title = title,
            description =
                row
                    .selectFirst(".entry-events-displayed > p:not([style])")
                    ?.textLines()
                    ?.joinToString("\n")
                    ?.blankToNull(),
            eventType = EventType.PARTY.name,
            eventDate = date,
            imageUrl = row.attrAt(".event-images-displayed img", "abs:src"),
            sourceUrl = sourceUrl,
            sourceId = "${EventSource.SURPRISE.sourceIdPrefix}$date",
            genre = row.dotSeparated("p.event-music").joinToString(", ").blankToNull(),
            priceBoxOffice = doorPrices.mapNotNull { euroAmounts(it.second).firstOrNull() }.minOrNull(),
            priceNote = doorPrices.joinToString(" · ") { (gate, price) -> "$gate: $price" }.blankToNull(),
            artists = row.dotSeparated("h6.event-music").map { ScrapedArtist(name = it.replace(DJ_PREFIX, "DJ "), role = ArtistRole.DJ) }
        )
    }

    private fun parseDate(row: Element): LocalDate? {
        val day = row.textAt(".events-displayed-show-day h3")
        val month = row.textAt(".events-displayed-month-yrs h6")
        val year = row.textAt(".events-displayed-month-yrs p")
        return try {
            LocalDate.parse("$day $month $year", DATE_FORMAT)
        } catch (_: DateTimeParseException) {
            null
        }
    }

    /** `Gate for Ladies` → `10,- € (incl. 1 Drink)`, each heading paired with the paragraph after it. */
    private fun doorPrices(row: Element): List<Pair<String, String>> =
        row.select(".entry-events-displayed > h6").mapNotNull { heading ->
            val gate = heading.text().removePrefix("Gate for").blankToNull()
            val price =
                heading
                    .nextElementSibling()
                    ?.takeIf { it.tagName() == "p" }
                    ?.text()
                    ?.collapseWhitespace()
                    ?.blankToNull()
            if (gate == null || price == null) null else gate to price
        }

    /** The items of a line the page separates with `ion-icon` dots, without the `--` that frames the DJ line. */
    private fun Element.dotSeparated(selector: String): List<String> {
        val line = selectFirst(selector)?.clone() ?: return emptyList()
        line.select("ion-icon").forEach { it.replaceWith(TextNode(SEPARATOR)) }
        return line
            .text()
            .split(SEPARATOR)
            .mapNotNull { it.trim(' ', '-').collapseWhitespace().blankToNull() }
    }

    private companion object {
        val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM uuuu", Locale.ENGLISH)
        val DJ_PREFIX = Regex("""^dj\s+""", RegexOption.IGNORE_CASE)
        const val SEPARATOR = "|"
    }
}
