package de.norm.events.scraper.altekantine

import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.UNRESOLVED_EVENT_DATE
import de.norm.events.scraper.imgSrcAt
import de.norm.events.scraper.parsePriceValue
import de.norm.events.scraper.textAt
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.Clock

/**
 * Pure HTML parser for an Alte Kantine event detail page (`?p=<id>`).
 *
 * Each post renders the event kind, price, date, start time and DJ as a
 * `ul.list-style-6` label/value list (`Wann:` / `Beginn:` / `Eintritt:` / `Was:` /
 * `DJ:`), the title as an `h2.heading-1`, the blurb in a `.line-height-28` text
 * block, and the poster as an `img.vc_single_image-img`.
 *
 * The detail page is authoritative for the kind, price, description, image and DJ —
 * the fields the overview lacks — and also re-states the date and start time, so a
 * successful detail fetch yields a complete event. The overview only fills the
 * subtitle gap and stands in entirely when the detail fetch fails, via
 * [AlteKantineWebsiteImporter.fillGapsFromOverview].
 *
 * @see AlteKantineOverviewPageScraper for overview parsing (discovery, date, fallback).
 * @see AlteKantineWebsiteImporter for the HTTP fetch orchestrator.
 * @see <a href="https://alte-kantine.eu/?p=12371">Example detail page</a>
 */
class AlteKantineDetailPageScraper(
    /** Clock for year inference on the year-less `Wann:` date. Defaults to the system clock; override in tests for determinism. */
    private val clock: Clock = Clock.systemDefaultZone()
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses an event detail page into a [ScrapedEvent], or `null` when the page has
     * no resolvable title or post id (an unexpected structure).
     *
     * @param sourceUrl the event's URL, used as [ScrapedEvent.sourceUrl] and to derive
     *   the [ScrapedEvent.sourceId].
     */
    @Suppress("ReturnCount") // Guard clauses for the missing title/post-id are clearer than nesting
    fun scrape(
        document: Document,
        sourceUrl: String
    ): ScrapedEvent? {
        val content = document.body()
        val title = titleFrom(document, content)
        if (title == null) {
            logger.warn { "Detail page at $sourceUrl has no event title, skipping" }
            return null
        }
        val postId = extractPostId(sourceUrl) ?: return null

        val eventType = alteKantineEventType(detailField(content, "Was"), title)
        val eintritt = detailField(content, "Eintritt")
        val boxOffice = parsePriceValue(eintritt)

        return ScrapedEvent(
            title = title,
            description = parseDescription(content),
            eventType = eventType,
            eventDate = parseAlteKantineDate(detailField(content, "Wann"), clock) ?: UNRESOLVED_EVENT_DATE,
            startTime = parseAlteKantineTime(detailField(content, "Beginn")),
            imageUrl = content.imgSrcAt("img.vc_single_image-img"),
            sourceUrl = sourceUrl,
            sourceId = "${EventSource.ALTE_KANTINE.sourceIdPrefix}$postId",
            // A clean numeric door price maps to the box office; keep the raw label as a note otherwise
            // (e.g. "frei", "mit Passwort"), where free-entry detection and the frontend can still use it.
            // A value carrying no letter or digit (e.g. a lone "€") is noise, so it is dropped.
            priceBoxOffice = boxOffice,
            priceNote = eintritt?.takeIf { boxOffice == null && it.any(Char::isLetterOrDigit) },
            artists = buildAlteKantineArtists(title, detailField(content, "DJ"), eventType)
        )
    }
}

/** Trailing " – Alte Kantine" site name on the page `<title>`, stripped to leave the event title. */
private val SITE_TITLE_SUFFIX = Regex("""\s*[–—-]\s*Alte Kantine\s*$""", RegexOption.IGNORE_CASE)

/**
 * Reads the event title from the `h2.heading-1` content heading, falling back to the
 * page `<title>` with the trailing " – Alte Kantine" site name stripped. Returns
 * `null` when neither yields a non-blank title.
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
 * Reads the value of the `ul.list-style-6` row whose `<label>` matches [label]
 * (ignoring the trailing colon and case), e.g. `"Wann"` → `"23.07."`, `"Eintritt"`
 * → `"4 €"`, or `null` when no such row exists. The label is a child `<label>`
 * element; the value is the list item's own trailing text.
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
 * Joins the blurb paragraphs from the `.line-height-28` text block into a single
 * description, dropping the `p.p1` line that merely echoes the title, or `null`
 * when the block is absent or empty.
 */
private fun parseDescription(content: Element): String? =
    content
        .selectFirst("div.line-height-28")
        ?.select("p")
        ?.filterNot { it.hasClass("p1") }
        ?.joinToString("\n\n") { it.text().trim() }
        ?.trim()
        ?.takeIf { it.isNotBlank() }
