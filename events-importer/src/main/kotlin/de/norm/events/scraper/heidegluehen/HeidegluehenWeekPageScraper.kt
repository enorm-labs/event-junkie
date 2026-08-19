package de.norm.events.scraper.heidegluehen

import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.imgSrcAt
import de.norm.events.scraper.isNonArtistName
import de.norm.events.scraper.textLines
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import java.time.LocalDate

/**
 * The DJ lineup for one party, read from Heideglühen's `/aktuell/` ("Diese Woche") page.
 *
 * @property date the party this lineup belongs to, so it can be matched to the month page.
 * @property artists the announced DJs, in the order the venue lists them.
 * @property imageUrl this party's own flyer, which supersedes the month page's shared artwork.
 */
data class HeidegluehenLineup(
    val date: LocalDate,
    val artists: List<ScrapedArtist>,
    val imageUrl: String?
) {
    /** Adds this lineup to the matching month-page event, leaving everything else untouched. */
    fun applyTo(event: ScrapedEvent): ScrapedEvent =
        event.copy(
            artists = artists,
            imageUrl = imageUrl ?: event.imageUrl
        )
}

/**
 * Pure HTML parser for Heideglühen's `/aktuell/` page, which carries **one** party — the imminent
 * one — and is the only place the venue publishes a lineup.
 *
 * The page shares the month page's markup, so the date and title parse the same way. What it adds
 * is a `Das Programm:` block naming the DJs one per line as `"Antal // Rush Hour, NL"` — the name,
 * then the label or city it is billed under — followed by `~~~` and a running order
 * (`"12:00-16:00 Forsberg"`). Only the names are stored: the model has no field for a set time, and
 * the running order repeats names the billing already listed.
 *
 * It also carries that party's own flyer where the month page has only one graphic for the month,
 * so the image comes along with the lineup.
 *
 * The lineup appears a few days before each party ("Das Programm folgt am Dienstag…" until then),
 * so on most days this page adds nothing and the event keeps the month page's data alone.
 *
 * @see HeidegluehenMonthPageScraper for the programme itself.
 * @see HeidegluehenWebsiteImporter for the HTTP fetch orchestrator.
 */
class HeidegluehenWeekPageScraper {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses the week page's lineup, or `null` when it names no date or has not published one yet.
     */
    fun scrape(document: Document): HeidegluehenLineup? {
        val paragraphs = document.select(".fl-rich-text p")
        val date = paragraphs.firstNotNullOfOrNull { p -> p.textLines().firstNotNullOfOrNull { parseSchedule(it)?.date } }
        val artists =
            paragraphs
                .flatMap { it.textLines() }
                .mapNotNull { parseBillingLine(it) }
                .distinct()
                .map { ScrapedArtist(name = it, role = DJ_ROLE) }

        return when {
            date == null -> {
                logger.info { "Heideglühen week page names no date; no lineup to apply" }
                null
            }

            artists.isEmpty() -> {
                logger.info { "Heideglühen has not published the lineup for $date yet" }
                null
            }

            // Where the month page carries one graphic for the whole month, this page carries the
            // party's own flyer, in the same slot.
            else -> {
                HeidegluehenLineup(date = date, artists = artists, imageUrl = document.imgSrcAt(MONTH_ARTWORK))
            }
        }
    }

    /**
     * Reads a DJ name off a `"Antal // Rush Hour, NL"` billing line, or `null` for any other line.
     *
     * The `//` is what marks a billing: every other line on the page — the date, the title, the
     * poem the venue opens with, the running order, the closing note — carries none.
     */
    private fun parseBillingLine(line: String): String? =
        line
            .takeIf { it.contains(BILLING_SEPARATOR) }
            ?.substringBefore(BILLING_SEPARATOR)
            ?.trim()
            ?.takeIf { it.isNotBlank() && !isNonArtistName(it) }
}

/** Separates a DJ from the label or city they are billed under. */
private const val BILLING_SEPARATOR = "//"

/** Every act at this open-air is a DJ; the venue books no live music. */
private const val DJ_ROLE = "DJ"
