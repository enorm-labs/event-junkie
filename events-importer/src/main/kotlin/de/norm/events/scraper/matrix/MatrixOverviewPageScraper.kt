package de.norm.events.scraper.matrix

import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.imgSrcAt
import de.norm.events.scraper.isNonArtistName
import de.norm.events.scraper.parseTime
import de.norm.events.scraper.splitSupportActs
import de.norm.events.scraper.textAt
import de.norm.events.scraper.textLinesAt
import de.norm.events.slug.SlugGenerator
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * Pure HTML parser for a single Matrix `/party-in-berlin/` month page.
 *
 * Every night of the month is one `div.toggled-item` whose `id` is a machine-readable `DD-MM-YYYY`
 * date — both the event date and the in-page anchor. The block's `.toggle-review` half (the expanded
 * "Details" view, rendered server-side even while collapsed) carries the full record; the
 * `.toggle-preview` half only repeats it with a CSS-truncated blurb and is therefore ignored:
 * - `p.text-sm` — `"Samstag 01.08.2026 | 22:00Uhr"`, read for the start time only (the date comes
 *   from the `id`);
 * - `p.text-lg strong` — the title, always the resident night's name (`"Matrix - Saturday"`);
 * - `li:has(i.fa-music) > span.d-block` — the `•`-separated genre list, rejoined with commas so
 *   `GenreNormalizer` (which does not treat `•` as a delimiter) can tokenize it;
 * - `li:has(i.fa-star) > span.d-block` — an optional starred promo line, stored as the subtitle;
 * - the first unlabelled `<p>` — the prose blurb, read as its `<br>`-delimited lines so the
 *   `► Entry :` price block below can be found by line rather than in one flattened run of text;
 * - `DJs:` and `Specials:` — labelled lists holding the lineup.
 *
 * Every event is typed [EventType.PARTY]: Matrix runs a DJ dance night daily, so the title is the
 * night's name and is never minted as an artist — the performers come from the `DJs:`/`Specials:`
 * lists instead. The `Floors:` list is deliberately not read: it names the rooms open that night but
 * the markup never says which DJ plays which floor (the orders do not correspond), so there is
 * nothing to put in [ScrapedArtist.stage] without guessing.
 *
 * @see MatrixWebsiteImporter for the fetch orchestration (entry page → next month → …).
 */
class MatrixOverviewPageScraper {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses all nights from one month page.
     *
     * @param baseUrl the URL the document was fetched from, used to rebuild each event's canonical
     *   month-page URL (see [monthPageUrl]).
     * @return one [ScrapedEvent] per night with a parseable date and title. The current-month page
     *   lists only the days still to come, so no past-date filtering is needed here; anything that
     *   does slip through is dropped centrally at persistence time (`EventUpsertService`).
     */
    fun scrape(
        document: Document,
        baseUrl: String
    ): List<ScrapedEvent> {
        val items = document.select("div.toggled-item[id]")
        logger.info { "Found ${items.size} event block(s) on Matrix month page $baseUrl" }

        @Suppress("TooGenericExceptionCaught") // Intentional: skip individual malformed nights without aborting the whole import.
        return items.mapNotNull { item ->
            try {
                parseEvent(item, baseUrl)
            } catch (e: Exception) {
                logger.warn(e) { "Failed to parse Matrix event block, skipping" }
                null
            }
        }
    }

    @Suppress("ReturnCount") // Guard clauses for the required date, detail half and title are clearer than nesting.
    private fun parseEvent(
        item: Element,
        baseUrl: String
    ): ScrapedEvent? {
        val anchor = item.id()
        val eventDate =
            parseAnchorDate(anchor) ?: run {
                logger.warn { "Could not parse Matrix date from block id '$anchor', skipping event" }
                return null
            }
        val review =
            item.selectFirst("div.toggle-review") ?: run {
                logger.warn { "No detail half in Matrix event block on $eventDate, skipping event" }
                return null
            }
        val title =
            review.textAt("p.text-lg strong") ?: run {
                logger.warn { "No title in Matrix event block on $eventDate, skipping event" }
                return null
            }

        val descriptionLines = review.textLinesAt(DESCRIPTION_QUERY)
        val (boxOffice, priceNote) = parseEntryPrices(descriptionLines)

        return ScrapedEvent(
            title = title,
            subtitle = review.textAt("li:has(i.fa-star) > span.d-block"),
            description = descriptionLines.joinToString("\n").takeIf { it.isNotBlank() },
            eventType = EventType.PARTY.name,
            eventDate = eventDate,
            startTime = parseStartTime(review.textAt("p.text-sm")),
            imageUrl = review.imgSrcAt("img.img-fluid"),
            sourceUrl = "${monthPageUrl(baseUrl, eventDate)}#$anchor",
            sourceId = "${EventSource.MATRIX.sourceIdPrefix}$eventDate-${SlugGenerator.slugify(title)}",
            genre = parseGenre(review),
            priceBoxOffice = boxOffice,
            priceNote = priceNote,
            artists = parseLineup(review)
        )
    }

    /** Parses the block's `DD-MM-YYYY` `id`, returning null when it is absent or not a date. */
    private fun parseAnchorDate(anchor: String?): LocalDate? {
        if (anchor.isNullOrBlank()) return null
        return try {
            LocalDate.parse(anchor.trim(), ANCHOR_DATE_FORMATTER)
        } catch (_: DateTimeParseException) {
            null
        }
    }

    /** Reads the `HH:mm` out of the `"Samstag 01.08.2026 | 22:00Uhr"` header line. */
    private fun parseStartTime(headerLine: String?): LocalTime? = parseTime(headerLine?.let { TIME_PATTERN.find(it)?.value })

    /**
     * The `•`-separated genre list rejoined with commas.
     *
     * `GenreNormalizer` splits on commas and slashes but not on the bullet the venue renders, so
     * handing it the raw run would produce one giant "afrobeats • house • top40" tag instead of three.
     */
    private fun parseGenre(review: Element): String? =
        review
            .textAt("li:has(i.fa-music) > span.d-block")
            ?.split(BULLET)
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?.takeIf { it.isNotEmpty() }
            ?.joinToString(", ")

    /**
     * The night's lineup: the `DJs:` list as [DJ][de.norm.events.event.ArtistRole.DJ] entries, then
     * the `Specials:` list (an MC or guest billed on top of the residents) as
     * [SUPPORT][de.norm.events.event.ArtistRole.SUPPORT]. Duplicates across the two lists collapse to
     * the first billing.
     */
    private fun parseLineup(review: Element): List<ScrapedArtist> {
        val djs = performerNames(review, DJS_LABEL).map { ScrapedArtist(name = it, role = "DJ") }
        val specials = performerNames(review, SPECIALS_LABEL).map { ScrapedArtist(name = it, role = "SUPPORT") }
        return (djs + specials).distinctBy { it.name.lowercase() }
    }

    /**
     * Reads the act names out of the `<ul>` that follows the `<p><strong>[label]:</strong></p>`
     * heading.
     *
     * Each `<li>` reads as `"<name> <small>(<label/affiliation>)</small> • <genre> • <genre>"`, so the
     * parenthesized `<small>` and the trailing genre run are dropped before the remainder is split
     * into acts — `"DJ JC & DJ GUS"` is two residents, while an alias in round brackets
     * (`"KORE (Eg0 B2B Kopolookoo)"`) survives as one.
     */
    private fun performerNames(
        review: Element,
        label: String
    ): List<String> =
        review
            .select("p:has(strong)")
            .firstOrNull { it.text().trim().startsWith(label) }
            ?.nextElementSibling()
            ?.takeIf { it.tagName() == "ul" }
            ?.select("li")
            ?.mapNotNull { performerName(it) }
            ?.flatMap { splitSupportActs(it) }
            ?.filterNot { isNonArtistName(it) }
            .orEmpty()

    /** Strips the `<small>` affiliation and the trailing `•`-separated genre run off one lineup `<li>`. */
    private fun performerName(item: Element): String? {
        val stripped = item.clone()
        stripped.select("small").remove()
        return stripped
            .text()
            .substringBefore(BULLET)
            .trim()
            .takeIf { it.isNotBlank() }
    }

    /**
     * Reads the door prices out of the blurb's `► Entry :` block: a bare `Entry`/`Eintritt` heading
     * line followed by one priced line per admission tier (`"10,00 € Ladies"`, `"12,00 € Gents"`).
     *
     * The venue prices by tier rather than by sales channel, so the tiers are kept verbatim in the
     * price note and the **lowest** of them becomes the box-office price — the "from" figure. There
     * is no presale: Matrix sells no advance tickets, only table reservations.
     *
     * The starred promo line ("Nur 5€ Eintritt für Ladies & Studenten bis 0 Uhr!") is deliberately
     * *not* part of the note. It is a conditional discount, not the admission price, and routing
     * "Freier Eintritt für Ladies bis 0 Uhr!" through `priceNote` would have `detectFree` mark a
     * 15 € night as free entry; it is stored as the subtitle instead.
     *
     * @return the lowest tier price and the tier breakdown, or `(null, null)` when the blurb carries
     *   no entry block (a handful of nights publish none).
     */
    private fun parseEntryPrices(descriptionLines: List<String>): Pair<BigDecimal?, String?> {
        val headingIndex = descriptionLines.indexOfFirst { ENTRY_HEADING.matches(it) }
        if (headingIndex < 0) return null to null
        val tiers =
            descriptionLines
                .drop(headingIndex + 1)
                .takeWhile { PRICE_VALUE.containsMatchIn(it) }
        val values =
            tiers.mapNotNull { tier ->
                PRICE_VALUE
                    .find(tier)
                    ?.groupValues
                    ?.get(1)
                    ?.let { BigDecimal(it.replace(",", ".")) }
            }
        return values.minOrNull() to tiers.joinToString(", ").takeIf { it.isNotBlank() }
    }

    /**
     * The canonical month-page URL for [date], rebuilt from the site root rather than reused verbatim
     * from [baseUrl].
     *
     * The current month is served both from the bare `/party-in-berlin/` entry URL and from its
     * explicit `?get_month=…&get_year=…` form; pinning every event to the explicit form keeps a
     * night's `sourceUrl` identical before and after the month rolls over, instead of rewriting ~30
     * rows on the first of each month.
     */
    private fun monthPageUrl(
        baseUrl: String,
        date: LocalDate
    ): String = "${baseUrl.substringBefore('?')}?get_month=${date.monthValue}&get_year=${date.year}"

    companion object {
        /** The `DD-MM-YYYY` date each night's block carries as its `id` / in-page anchor. */
        private val ANCHOR_DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("d-M-yyyy")

        /** The `HH:mm` in the `"Samstag 01.08.2026 | 22:00Uhr"` header line. */
        private val TIME_PATTERN = Regex("""\d{1,2}:\d{2}""")

        /**
         * The blurb: the first `<p>` that carries neither a class (excluding the `p.text-sm` /
         * `p.text-lg` header lines) nor a `<strong>` section label (excluding `Floors:` / `DJs:` /
         * `Specials:`).
         */
        private const val DESCRIPTION_QUERY = "p:not([class]):not(:has(strong))"

        /** The bullet the venue puts between genres, and between a DJ and the genres they play. */
        private const val BULLET = "•"

        private const val DJS_LABEL = "DJs"
        private const val SPECIALS_LABEL = "Specials"

        /** The bare `► Entry :` / `Eintritt:` heading line that opens the door-price block. */
        private val ENTRY_HEADING = Regex("""[►>\s]*(?:entry|eintritt)\s*:?\s*""", RegexOption.IGNORE_CASE)

        /** A single monetary value on an admission-tier line, accepting a German or dot decimal separator. */
        private val PRICE_VALUE = Regex("""(\d+(?:[.,]\d{1,2})?)\s*€""")
    }
}
