package de.norm.events.scraper.astra

import de.norm.events.event.ArtistRole
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.UNRESOLVED_EVENT_DATE
import de.norm.events.scraper.blankToNull
import de.norm.events.scraper.extractEventSlug
import de.norm.events.scraper.hrefAt
import de.norm.events.scraper.isNonArtistName
import de.norm.events.scraper.paragraphText
import de.norm.events.scraper.parsePresaleAndBoxOfficePrices
import de.norm.events.scraper.splitBackToBack
import de.norm.events.scraper.splitSupportActs
import de.norm.events.scraper.textLines
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Pure HTML parser for Astra Kulturhaus event detail pages — the **primary data source**.
 *
 * Reuses the overview's `.event__*` header markup ([parseAstraEventBlock]) and adds the
 * detail-only fields: promoter(s) (`.promoters__link`), presale / box-office prices
 * (`.prices .price`), ticket shop URL (`.purchase-option__button`), description / artist bio
 * (`.gig__description`, `.detail__description`).
 *
 * No artist roster here, so `artists` is left to the overview via
 * [AstraWebsiteImporter.fillGapsFromOverview]. The one exception is a `Line-Up:` block in the
 * description ([parseLineup]), which the importer uses only when the overview billed nobody (#4270).
 * A `kind` label may render, but as the raw
 * per-day value without the overview's festival-day normalization, so the overview type wins
 * and the detail value is only a fallback.
 *
 * @see AstraOverviewPageScraper for overview parsing (event type, discovery).
 * @see AstraWebsiteImporter for the HTTP fetch orchestrator.
 * @see <a href="https://www.astra-berlin.de/events/2026-05-18-green-lung">Example detail page</a>
 */
class AstraDetailPageScraper {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses a detail page into a [ScrapedEvent], scoped to `main.page-content` (the single
     * event). `null` when the container or the title is missing.
     *
     * @param sourceUrl the event's URL: [ScrapedEvent.sourceUrl] and the [ScrapedEvent.sourceId].
     */
    fun scrape(
        document: Document,
        sourceUrl: String
    ): ScrapedEvent? {
        val content = document.selectFirst("main.page-content") ?: document.body()
        val block = parseAstraEventBlock(content, sourceUrl)
        if (block == null) {
            logger.warn { "Detail page has no event title, skipping" }
            return null
        }

        val (pricePresale, priceBoxOffice) = parsePresaleAndBoxOfficePrices(content.select(".prices .price"))

        return ScrapedEvent(
            title = block.title,
            subtitle = block.subtitle,
            // The banner leads the description, the way the Lido template stores it.
            description = listOfNotNull(block.notice, parseDescription(content)).joinToString("\n").ifBlank { null },
            eventType = block.eventType,
            // Detail pages always carry the real date; sentinel only if absent (then fillGapsFromOverview).
            eventDate = block.eventDate ?: UNRESOLVED_EVENT_DATE,
            doorsTime = block.doorsTime,
            startTime = block.startTime,
            imageUrl = block.imageUrl,
            sourceUrl = sourceUrl,
            sourceId = "${EventSource.ASTRA.sourceIdPrefix}${extractEventSlug(sourceUrl)}",
            ticketUrl = content.hrefAt(".purchase-option__button"),
            pricePresale = pricePresale,
            priceBoxOffice = priceBoxOffice,
            soldOut = block.soldOut,
            status = block.status,
            promoters = parsePromoters(content),
            promoterWebsites = parsePromoterWebsites(content),
            artists = parseLineup(content)
        )
    }

    /**
     * The acts of a `Line-Up:` block in the description (#4270): the label on its own `<br>` line or
     * leading the first act, then one act per line up to a blank line or the paragraph's end. A line
     * too long for a name is prose and ends the block too. Set times come off. The first act heads
     * the bill and the rest support, as [artistsOf] bills a subtitle line-up (#3154).
     */
    private fun parseLineup(content: Element): List<ScrapedArtist> =
        content
            .select(".gig__description p, .detail__description p")
            .firstNotNullOfOrNull { lineupLines(it.textLines(keepBlankLines = true)) }
            .orEmpty()
            .map { it.replace(SET_TIME, "").trim() }
            .flatMap(::splitBackToBack)
            .flatMap(::splitSupportActs)
            .filterNot(::isNonArtistName)
            .distinctBy { it.lowercase() }
            .mapIndexed { i, name -> ScrapedArtist(name = name, role = if (i == 0) ArtistRole.HEADLINER else ArtistRole.SUPPORT) }

    /** The lines after the `Line-Up:` label in one paragraph's [lines], or `null` without a label. */
    private fun lineupLines(lines: List<String>): List<String>? {
        val labelIndex = lines.indexOfFirst { LINEUP_LABEL.containsMatchIn(it) }
        if (labelIndex < 0) return null
        val sameLine = lines[labelIndex].replaceFirst(LINEUP_LABEL, "").trim()
        return (listOfNotNull(sameLine.ifBlank { null }) + lines.drop(labelIndex + 1))
            .takeWhile { it.isNotBlank() && it.split(WHITESPACE).size <= MAX_NAME_WORDS }
    }

    /**
     * Promoter names from the `.promoters__link` anchors, deduplicated in order (the markup repeats
     * them for mobile/desktop layouts).
     */
    private fun parsePromoters(content: Element): List<String> =
        content
            .select(".promoters__link")
            .mapNotNull { it.text().trim().takeIf { name -> name.isNotBlank() } }
            .distinct()

    /** Each promoter anchor's `href`, where it links out (#1319). */
    private fun parsePromoterWebsites(content: Element): Map<String, String> =
        content
            .select(".promoters__link")
            .mapNotNull { link ->
                val name = link.text().blankToNull() ?: return@mapNotNull null
                val url = link.absUrl("href").takeIf { it.startsWith("http") } ?: return@mapNotNull null
                name to url
            }.toMap()

    /**
     * Description from `.gig__description` (per-artist bios) and `.detail__description` (event
     * blurb), deduplicated in order since both layouts can repeat content. `null` without prose.
     */
    private fun parseDescription(content: Element): String? =
        content
            .select(".gig__description p, .detail__description p")
            .paragraphText(distinct = true)

    private companion object {
        /** `Line-Up:`, `Lineup:` or `Line up:` opening a line; the colon keeps prose that starts so out. */
        val LINEUP_LABEL = Regex("""^\s*line\s*-?\s*up\s*:\s*""", RegexOption.IGNORE_CASE)

        /** One printed slot: `23:30`, `22.00 Uhr`, `(23:30 - 01:00)`. */
        const val SLOT = """\(?\d{1,2}[:.]\d{2}(?:\s*[-–]\s*\d{1,2}[:.]\d{2})?(?:\s*uhr)?\)?"""

        /** A set time before or after the act: `22:00 Alle Farben`, `Lahos (23:30 - 01:00 Uhr)`. */
        val SET_TIME = Regex("""^$SLOT\s*[-–|:]?\s*|\s*[-–|]?\s*$SLOT\s*$""", RegexOption.IGNORE_CASE)

        val WHITESPACE = Regex("""\s+""")

        /** More words than this on one line is a sentence, not an act. */
        const val MAX_NAME_WORDS = 8
    }
}
