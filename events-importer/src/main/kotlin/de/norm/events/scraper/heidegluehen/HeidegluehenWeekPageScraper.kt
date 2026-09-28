package de.norm.events.scraper.heidegluehen

import de.norm.events.scraper.BERLIN
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.imgSrcAt
import de.norm.events.scraper.isNonArtistName
import de.norm.events.scraper.parseTime
import de.norm.events.scraper.textLines
import de.norm.events.slug.SlugGenerator
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/**
 * The DJ lineup for one party, read from Heideglühen's `/aktuell/` ("Diese Woche") page.
 *
 * @property date the party this lineup belongs to, matched to the month page.
 * @property artists the announced DJs, in the venue's order.
 * @property imageUrl this party's own flyer, superseding the month page's shared artwork.
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
 * Pure HTML parser for Heideglühen's `/aktuell/` page, which carries **one** party — the
 * imminent one — and is the only place the venue publishes a lineup.
 *
 * Same markup as the month page, so date and title parse the same way. It adds a
 * `Das Programm:` block naming the DJs one per line as `"Antal // Rush Hour, NL"` — name, then
 * the label or city billed under — followed by `~~~` and a running order (`"12:00-16:00
 * Forsberg"`). The billing is the lineup; the running order only times it (#2002), so a slot
 * naming nobody billed (`"21:00-22:00 Finale b2b"`) adds no act. It also carries that party's
 * own flyer where the month page has one graphic for the month, so the image comes along.
 *
 * The lineup appears a few days before each party ("Das Programm folgt am Dienstag…" until
 * then), so on most days this page adds nothing and the event keeps the month page's data.
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
        val lines = paragraphs.flatMap { it.textLines() }
        val sets = if (date == null) emptyMap() else parseRunningOrder(lines, date)
        val artists =
            lines
                .mapNotNull { parseBillingLine(it) }
                .distinct()
                .map { name ->
                    val set = sets[SlugGenerator.slugify(name)]
                    ScrapedArtist(name = name, role = DJ_ROLE, setStart = set?.first, setEnd = set?.second)
                }

        return when {
            date == null -> {
                logger.info { "Heideglühen week page names no date; no lineup to apply" }
                null
            }

            artists.isEmpty() -> {
                logger.info { "Heideglühen has not published the lineup for $date yet" }
                null
            }

            // Where the month page carries one graphic for the whole month, this page carries the party's
            // own flyer, in the same slot.
            else -> {
                HeidegluehenLineup(date = date, artists = artists, imageUrl = document.imgSrcAt(MONTH_ARTWORK))
            }
        }
    }

    /**
     * Each running-order slot's start and end, keyed by the slug of the name it gives. The party
     * runs from Saturday noon into Sunday night: the first slot is on the party's [date], a slot
     * starting earlier than the one before has crossed midnight, and an end at or before its
     * start is the next day. A name given twice keeps its first slot.
     */
    private fun parseRunningOrder(
        lines: List<String>,
        date: LocalDate
    ): Map<String, Pair<Instant, Instant?>> {
        var day = date
        var previous: LocalTime? = null
        val sets = mutableMapOf<String, Pair<Instant, Instant?>>()
        for (match in lines.mapNotNull { RUNNING_ORDER_LINE.matchEntire(it.trim()) }) {
            val (startText, endText, name) = match.destructured
            val start = parseTime(startText.replace('.', ':')) ?: continue
            val end = parseTime(endText.replace('.', ':'))
            if (previous != null && start < previous) day = day.plusDays(1)
            previous = start
            val endDay = if (end != null && end <= start) day.plusDays(1) else day
            sets.putIfAbsent(
                SlugGenerator.slugify(name.trim()),
                day.atTime(start).atZone(BERLIN).toInstant() to end?.let { endDay.atTime(it).atZone(BERLIN).toInstant() }
            )
        }
        return sets
    }

    /**
     * A DJ name off a `"Antal // Rush Hour, NL"` billing line, or `null` for any other line. The
     * `//` marks a billing: every other line — date, title, the poem the venue opens with, running
     * order, closing note — carries none.
     */
    private fun parseBillingLine(line: String): String? =
        line
            .takeIf { it.contains(BILLING_SEPARATOR) }
            ?.substringBefore(BILLING_SEPARATOR)
            ?.trim()
            ?.takeIf { it.isNotBlank() && !isNonArtistName(it) }
}

/** A running-order line: `"12:00-16:00 Forsberg"`, the separator a hyphen or an en dash. */
private val RUNNING_ORDER_LINE = Regex("""(\d{2}[:.]\d{2})\s*[-–]\s*(\d{2}[:.]\d{2})\s+(.+)""")

/** Separates a DJ from the label or city they are billed under. */
private const val BILLING_SEPARATOR = "//"

/** Every act at this open-air is a DJ; the venue books no live music. */
private const val DJ_ROLE = "DJ"
