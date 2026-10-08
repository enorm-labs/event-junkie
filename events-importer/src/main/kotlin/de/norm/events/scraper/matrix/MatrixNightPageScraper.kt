package de.norm.events.scraper.matrix

import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.attrAt
import de.norm.events.scraper.euroAmounts
import de.norm.events.scraper.isNonArtistName
import de.norm.events.scraper.parseGermanDate
import de.norm.events.scraper.resolveUrl
import de.norm.events.scraper.splitBackToBack
import de.norm.events.scraper.splitSupportActs
import de.norm.events.scraper.textAt
import de.norm.events.slug.SlugGenerator
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import java.time.LocalTime

/**
 * Pure HTML parser for one Matrix night page, `/de/night/<format>`.
 *
 * Matrix runs one recurring format per weekday, and its page shows only that format's **next** date.
 * The page is server-rendered, so every field is in the HTML:
 * - `h1.nd-name` — the format's name (`Social`), which is the title;
 * - the first `.eyebrow` holding a `DD.MM.YYYY` date (`Do., 08.10.2026`) — the event date;
 * - `p.nd-genre` — the `·`-separated genre list, rejoined with commas for `GenreNormalizer`;
 * - `.nd-djs .nd-dj b` — one line-up entry each, read as `DJ` (see [parseLineup]);
 * - `p.nd-deal` — an optional door offer (see [priceNote]);
 * - `Einlass ab 22 Uhr` in the side column — the start time. The page names no end, so a night that
 * runs past midnight has no end time, as on the old site.
 *
 * Every event is [EventType.PARTY]: the title is the night's name and never an artist.
 *
 * @see MatrixWebsiteImporter for the fetch orchestration (home page → night pages).
 */
class MatrixNightPageScraper {
    private val logger = KotlinLogging.logger {}

    /**
     * The night on [document], or null when the page shows no title or no date — a format with no
     * next date announced.
     *
     * @param url the night page's URL, which is the event's `sourceUrl`.
     */
    @Suppress("ReturnCount") // Guard clauses for the required title and date are clearer than nesting.
    fun scrape(
        document: Document,
        url: String
    ): ScrapedEvent? {
        val title =
            document.textAt("h1.nd-name") ?: run {
                logger.warn { "No format name on Matrix night page $url, skipping" }
                return null
            }
        val eventDate =
            document
                .select(".eyebrow")
                .firstNotNullOfOrNull { eyebrow -> DATE_PATTERN.find(eyebrow.text())?.let { parseGermanDate(it.value) } }
                ?: run {
                    logger.warn { "No date on Matrix night page $url, skipping '$title'" }
                    return null
                }
        return ScrapedEvent(
            title = title,
            description = description(document),
            eventType = EventType.PARTY.name,
            eventDate = eventDate,
            startTime = parseStartTime(document),
            imageUrl = heroImage(document, url),
            sourceUrl = url,
            sourceId = "${EventSource.MATRIX.sourceIdPrefix}$eventDate-${SlugGenerator.slugify(title)}",
            genre = parseGenre(document),
            priceNote = priceNote(document),
            artists = parseLineup(document)
        )
    }

    /**
     * The night's text: the tagline, the floors line (`1 Floor geöffnet`) and the door offer, one per
     * line.
     */
    private fun description(document: Document): String? =
        listOfNotNull(
            document.textAt("p.nd-tag"),
            document.textAt(".nd-entry li > span"),
            document.textAt("p.nd-deal")
        ).joinToString("\n").takeIf { it.isNotBlank() }

    /** The `HH` (and optional `:mm`) out of `Einlass ab 22 Uhr`. */
    private fun parseStartTime(document: Document): LocalTime? =
        document
            .select(".nd-side p")
            .firstNotNullOfOrNull { DOORS_PATTERN.find(it.text()) }
            ?.let { match -> LocalTime.of(match.groupValues[1].toInt() % HOURS_PER_DAY, match.groupValues[2].ifEmpty { "0" }.toInt()) }

    /** The format's hero image, a CSS `background-image` on `.nd-hero-art`, resolved against [url]. */
    private fun heroImage(
        document: Document,
        url: String
    ): String? =
        document
            .attrAt(".nd-hero-art", "style")
            ?.let { BACKGROUND_URL.find(it)?.groupValues?.get(1) }
            ?.trim('"', '\'')
            ?.let { resolveUrl(url, it) }

    private fun parseGenre(document: Document): String? =
        document
            .textAt("p.nd-genre")
            ?.split(GENRE_SEPARATOR)
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?.takeIf { it.isNotEmpty() }
            ?.joinToString(", ")

    /**
     * The door offer as the price note, only where it names a € amount (`Nur 5 € Eintritt für Ladies &
     * Studenten bis 0 Uhr!`). One naming none (`Freier Eintritt für Ladies bis 0 Uhr!`) is left out:
     * in the note, `detectFree` would mark a paid night free. Matrix publishes no door price, so
     * the offer is never priced.
     */
    private fun priceNote(document: Document): String? = document.textAt("p.nd-deal")?.takeIf { euroAmounts(it).isNotEmpty() }

    /**
     * The line-up, every entry as `DJ`, duplicates collapsed. An entry reads `DJ JC b2b DJ GUS` or
     * `DJ TC (Blactro)`: the trailing parenthesis is the resident's crew or radio show, which the old
     * site set apart in `<small>`. It is dropped so `DJ TC` stays one artist across both sites. Then
     * `b2b`, `&` and commas split the entry into acts, and a leading `Deejay` reads as `DJ`.
     */
    private fun parseLineup(document: Document): List<ScrapedArtist> =
        document
            .select(".nd-djs .nd-dj b")
            .asSequence()
            .map { it.text().trim() }
            .map { it.replace(TRAILING_AFFILIATION, "").trim().ifEmpty { it } }
            .flatMap { splitBackToBack(it) }
            .flatMap { splitSupportActs(it) }
            .map { it.replace(DEEJAY_PREFIX, "DJ ") }
            .filterNot { isNonArtistName(it) }
            .distinctBy { it.lowercase() }
            .map { ScrapedArtist(name = it, role = "DJ") }
            .toList()

    private companion object {
        /** The `DD.MM.YYYY` in the date eyebrow, `Do., 08.10.2026`. */
        val DATE_PATTERN = Regex("""\d{1,2}\.\d{1,2}\.\d{4}""")

        /** `Einlass ab 22 Uhr`, or `22:30 Uhr`. */
        val DOORS_PATTERN = Regex("""Einlass ab (\d{1,2})(?:[:.](\d{2}))?\s*Uhr""", RegexOption.IGNORE_CASE)

        /** `24 Uhr` is midnight. */
        const val HOURS_PER_DAY = 24

        val BACKGROUND_URL = Regex("""background-image:\s*url\(([^)]+)\)""")

        const val GENRE_SEPARATOR = "·"

        /** A parenthesis at the end of a line-up entry, `DJ R2V (KISS FM)`. */
        val TRAILING_AFFILIATION = Regex("""\s*\([^()]*\)\s*$""")

        /** The spelled-out `Deejay` in front of a DJ's name. */
        val DEEJAY_PREFIX = Regex("""^deejay\s+""", RegexOption.IGNORE_CASE)
    }
}
