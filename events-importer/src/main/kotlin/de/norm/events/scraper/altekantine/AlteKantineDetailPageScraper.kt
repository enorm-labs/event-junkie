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
            // A clean numeric door price maps to the box office; otherwise the raw label is a note
            // ("frei", "mit Passwort") that free-entry detection and the frontend can still use. A value
            // with no letter or digit (a lone "€") is noise and dropped.
            priceBoxOffice = boxOffice,
            priceNote = eintritt?.takeIf { boxOffice == null && it.any(Char::isLetterOrDigit) },
            artists = buildAlteKantineArtists(title, detailField(content, "DJ"), eventType)
        )
    }
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
