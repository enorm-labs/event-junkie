package de.norm.events.scraper.altekantine

import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.UNRESOLVED_EVENT_DATE
import de.norm.events.scraper.euroAmounts
import de.norm.events.scraper.hasFreeEntryPhrase
import de.norm.events.scraper.imgSrcAt
import de.norm.events.scraper.parsePriceValue
import de.norm.events.scraper.textAt
import de.norm.events.scraper.textLines
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.math.BigDecimal
import java.time.Clock

/**
 * Pure HTML parser for an Alte Kantine event detail page (`?p=<id>`).
 *
 * Each post renders kind, price, date, start time and DJ as a `ul.list-style-6` label/value
 * list (`Wann:` / `Beginn:` / `Eintritt:` / `Was:` / `DJ:`), the title as `h2.heading-1`, the
 * blurb in a `.line-height-28` block, and the poster as `img.vc_single_image-img`.
 *
 * Authoritative for kind, price, description, image and DJ — what the overview lacks — and
 * restates date and start time, so a successful fetch is a complete event. The overview only
 * fills the subtitle gap and stands in entirely when the fetch fails, via
 * [AlteKantineWebsiteImporter.fillGapsFromOverview].
 *
 * @see AlteKantineOverviewPageScraper for overview parsing (discovery, date, fallback).
 * @see AlteKantineWebsiteImporter for the HTTP fetch orchestrator.
 * @see <a href="https://alte-kantine.eu/?p=12371">Example detail page</a>
 */
class AlteKantineDetailPageScraper(
    /** Clock for year inference on the year-less `Wann:` date; override in tests. */
    private val clock: Clock = Clock.systemDefaultZone()
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses a detail page into a [ScrapedEvent], or `null` without a resolvable title or post id.
     *
     * @param sourceUrl the event's URL: [ScrapedEvent.sourceUrl] and the [ScrapedEvent.sourceId].
     */
    @Suppress("ReturnCount") // Guard clauses for the missing title/post-id are clearer than nesting
    fun scrape(
        document: Document,
        sourceUrl: String
    ): ScrapedEvent? {
        val content = document.body()
        val title = titleFrom(document, content)
        if (title == null) {
            logger.warn { "Detail page has no event title, skipping" }
            return null
        }
        val postId = extractPostId(sourceUrl) ?: return null

        val eventType = alteKantineEventType(detailField(content, "Was"), title)
        val eintritt = detailField(content, "Eintritt")
        val door = parsePriceValue(eintritt)
        val earlyEntry = door?.let { earlyEntryTier(content, it) }
        val boxOffice = earlyEntry?.amount ?: door

        return ScrapedEvent(
            title = title,
            description = parseDescription(content),
            eventType = eventType,
            eventDate = parseAlteKantineDate(detailField(content, "Wann"), clock) ?: UNRESOLVED_EVENT_DATE,
            startTime = parseAlteKantineTime(detailField(content, "Beginn")),
            imageUrl = content.imgSrcAt("img.vc_single_image-img"),
            sourceUrl = sourceUrl,
            sourceId = "${EventSource.ALTE_KANTINE.sourceIdPrefix}$postId",
            // A clean numeric door price maps to the box office; otherwise the raw label is a note
            // ("frei", "mit Passwort") that free-entry detection and the frontend can still use. A value
            // with no letter or digit (a lone "€") is noise and dropped. A night that leaves the field empty
            // may still say it in the text: the Beer Pong nights' "Freier Eintritt für Alle". A cheaper early
            // entry is the price, with both tiers in the note (#2083).
            priceBoxOffice = boxOffice,
            priceNote =
                earlyEntry?.note
                    ?: eintritt?.takeIf { boxOffice == null && it.any(Char::isLetterOrDigit) }
                    ?: freeEntryLine(content).takeIf { boxOffice == null },
            artists = buildAlteKantineArtists(title, detailField(content, "DJ"), eventType)
        )
    }
}

/** The description's own line stating free entry. */
private fun freeEntryLine(content: Element): String? = descriptionLines(content).firstOrNull(::hasFreeEntryPhrase)

/** The description's lines, read per `<br>` line rather than per paragraph. */
private fun descriptionLines(content: Element): List<String> =
    content
        .selectFirst("div.line-height-28")
        ?.select("p")
        ?.flatMap { it.textLines() }
        ?.map { it.trim() }
        .orEmpty()

/** A door price cheaper until [until] than the [later] one from the field list. */
private data class EarlyEntryTier(
    val amount: BigDecimal,
    /** When the tier ends, as printed: `0 Uhr`, `Mitternacht`. */
    val until: String,
    val later: BigDecimal
) {
    /** Both tiers, as `4 € bis 0 Uhr, danach 7 €`. */
    val note: String get() = "${euros(amount)} bis $until, danach ${euros(later)}"
}

/**
 * The early-entry tier from the description's `Eintritt bis 0 Uhr: 4€` line (Kantine Deluxe), when it is below
 * the field's [door] price. A tier that needs a password (`Eintritt mit Passwort … bis Mitternacht 2€`) is a
 * conditional discount, not a door tier, so it stays in the description.
 */
private fun earlyEntryTier(
    content: Element,
    door: BigDecimal
): EarlyEntryTier? =
    descriptionLines(content).firstNotNullOfOrNull { line ->
        EARLY_ENTRY_LINE.matchEntire(line)?.let { match ->
            euroAmounts(match.groupValues[2])
                .singleOrNull()
                ?.takeIf { it < door }
                ?.let { EarlyEntryTier(it, match.groupValues[1], door) }
        }
    }

/** `Eintritt bis 0 Uhr: 4€`, `Eintritt bis 23:30 Uhr 3 €`, `Eintritt bis Mitternacht: 4 €`. */
private val EARLY_ENTRY_LINE =
    Regex("""Eintritt\s+bis\s+(\d{1,2}(?:[:.]\d{2})?\s*Uhr|Mitternacht)\s*:?\s*(.+)""", RegexOption.IGNORE_CASE)

/** A figure in the note's German form: `4 €`, `4,50 €`. */
private fun euros(amount: BigDecimal): String {
    val plain = amount.stripTrailingZeros()
    val figure = if (plain.scale() <= 0) plain.toBigInteger().toString() else plain.setScale(2).toPlainString().replace('.', ',')
    return "$figure €"
}

/** Trailing " – Alte Kantine" site name on the page `<title>`, stripped to leave the event title. */
private val SITE_TITLE_SUFFIX = Regex("""\s*[–—-]\s*Alte Kantine\s*$""", RegexOption.IGNORE_CASE)

/**
 * The title from `h2.heading-1`, else the page `<title>` minus " – Alte Kantine"; `null` when
 * neither yields a non-blank title.
 */
private fun titleFrom(
    document: Document,
    content: Element
): String? =
    content.textAt("h2.heading-1")
        ?: document
            .title()
            .replace(SITE_TITLE_SUFFIX, "")
            .trim()
            .takeIf { it.isNotBlank() }

/**
 * The value of the `ul.list-style-6` row whose `<label>` matches [label] (ignoring trailing
 * colon and case), e.g. `"Wann"` → `"23.07."`, `"Eintritt"` → `"4 €"`, or `null`. The label
 * is a child `<label>`; the value is the list item's own trailing text.
 */
private fun detailField(
    content: Element,
    label: String
): String? =
    content
        .select("ul.list-style-6 li")
        .firstOrNull {
            it
                .selectFirst("label")
                ?.text()
                ?.trim()
                ?.trimEnd(':')
                ?.trim()
                .equals(label, ignoreCase = true)
        }?.ownText()
        ?.trim()
        ?.takeIf { it.isNotBlank() }

/**
 * The blurb paragraphs from `.line-height-28` joined, minus the `p.p1` line that echoes the
 * title; `null` when absent or empty.
 */
private fun parseDescription(content: Element): String? =
    content
        .selectFirst("div.line-height-28")
        ?.select("p")
        ?.filterNot { it.hasClass("p1") }
        ?.joinToString("\n\n") { it.text().trim() }
        ?.trim()
        ?.takeIf { it.isNotBlank() }
