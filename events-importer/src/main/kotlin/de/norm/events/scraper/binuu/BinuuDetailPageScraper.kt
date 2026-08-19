package de.norm.events.scraper.binuu

import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.UNRESOLVED_EVENT_DATE
import de.norm.events.scraper.isNonArtistName
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import tools.jackson.databind.JsonNode

/**
 * Pure parser for Bi Nuu event detail pages (`/de/events/<id>`).
 *
 * The detail page is the **primary data source** for each event: its embedded
 * `data.item` payload (see [BinuuSvelteKitPayload]) is a superset of the overview
 * entry, adding doors time, description (`text`), ticket URL, promoters, and the
 * `performers` roster on top of the shared title/date/image/sold-out/status fields.
 *
 * Artist roles are derived from the site's own structured data rather than by
 * guessing the title: every name in `performers` is kept, and a performer is
 * tagged `SUPPORT` when it appears in the `subtitle_2` support/guest line
 * (otherwise `HEADLINER`). If `subtitle_2` marks every performer as support, the
 * first performer is promoted to headliner so an event with a roster always has one.
 *
 * @see BinuuOverviewPageScraper for overview parsing (discovery, fallback).
 * @see BinuuWebsiteImporter for the HTTP fetch orchestrator.
 * @see <a href="https://binuu.de/de/events/inzpqdgvi1eab2q">Example detail page</a>
 */
class BinuuDetailPageScraper {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses an event detail page into a [ScrapedEvent], or `null` when the page
     * carries no parseable `item` payload or is missing the required id/title.
     *
     * @param sourceUrl the event's URL, used as [ScrapedEvent.sourceUrl] and to
     *   confirm the [ScrapedEvent.sourceId].
     */
    @Suppress("ReturnCount") // Guard clauses for missing payload, id, and title are clearer than nesting
    fun scrape(
        document: Document,
        sourceUrl: String
    ): ScrapedEvent? {
        val item = BinuuSvelteKitPayload.dataNode(document, "item", '{')
        if (item == null || !item.isObject) {
            logger.warn { "Detail page at $sourceUrl has no Bi Nuu item payload, skipping" }
            return null
        }
        val id = item.stringOrNull("id") ?: item.stringOrNull("dbId")
        if (id == null) {
            logger.warn { "Detail page at $sourceUrl has no event id, skipping" }
            return null
        }
        val title = item.stringOrNull("title")
        if (title == null) {
            logger.warn { "Detail page at $sourceUrl has no event title, skipping" }
            return null
        }

        val start = item.stringOrNull("start")
        val subtitle = item.stringOrNull("subtitle")
        val description = parseDescription(item.stringOrNull("text"))
        return ScrapedEvent(
            title = title,
            subtitle = subtitle,
            description = description,
            // Bi Nuu carries no category field, so the type is inferred from the
            // title/subtitle (see inferBinuuEventType).
            eventType = inferBinuuEventType(title, subtitle),
            // Detail pages carry the real date; sentinel only if absent, then the
            // overview value is adopted via BinuuWebsiteImporter.fillGapsFromOverview.
            eventDate = parseBinuuDate(start) ?: UNRESOLVED_EVENT_DATE,
            doorsTime = parseBinuuTime(item.stringOrNull("doors")),
            startTime = parseBinuuTime(start),
            imageUrl = item.binuuImageUrl(),
            sourceUrl = sourceUrl,
            sourceId = "${EventSource.BINUU.sourceIdPrefix}$id",
            ticketUrl = parseTicketUrl(item),
            soldOut = item.path("soldout").asBoolean(),
            status = mapBinuuStatus(item.stringOrNull("eventStatus")),
            artists = parseArtists(item),
            promoters = parsePromoters(item)
        )
    }

    /**
     * Cleans the HTML `text` blurb into plain text: paragraphs are joined with
     * newlines, falling back to the flattened text when no `<p>` is present.
     * Returns `null` for missing or empty prose.
     */
    private fun parseDescription(html: String?): String? {
        if (html.isNullOrBlank()) return null
        val fragment = Jsoup.parseBodyFragment(html)
        val paragraphs =
            fragment
                .select("p")
                .map { it.text().trim() }
                .filter { it.isNotBlank() }
        val text = if (paragraphs.isNotEmpty()) paragraphs.joinToString("\n") else fragment.text().trim()
        return text.takeIf { it.isNotBlank() }
    }

    /**
     * Returns the first ticket-shop URL from the `tickets[]` array, stripping any
     * stray whitespace the CMS leaves in the value (e.g. a rogue space after `?`).
     * Returns `null` when no absolute ticket URL is present (some events sell only
     * via a link buried in the description).
     */
    private fun parseTicketUrl(item: JsonNode): String? =
        item.path("tickets").firstNotNullOfOrNull { ticket ->
            ticket.stringOrNull("url")?.filterNot(Char::isWhitespace)?.takeIf { it.startsWith("http") }
        }

    /** Extracts promoter names from the `promoters[]` array, deduplicated in order. */
    private fun parsePromoters(item: JsonNode): List<String> =
        item
            .path("promoters")
            .mapNotNull { it.stringOrNull("title") }
            .distinct()

    /**
     * Builds the artist roster from the structured `performers` list, tagging a
     * performer `SUPPORT` when its name appears in the `subtitle_2` support/guest
     * line and `HEADLINER` otherwise. Non-artist names (placeholders, festival
     * labels, …) are dropped. If every performer was tagged support, the first is
     * promoted to headliner so a roster always has a headliner.
     */
    private fun parseArtists(item: JsonNode): List<ScrapedArtist> {
        val performers = item.stringList("performers").filterNot { isNonArtistName(it) }
        if (performers.isEmpty()) return emptyList()

        val supportLine = item.stringOrNull("subtitle_2")?.lowercase().orEmpty()
        val supportFlags = performers.map { supportLine.isNotBlank() && supportLine.contains(it.lowercase()) }
        val hasHeadliner = supportFlags.any { !it }

        return performers.mapIndexed { index, name ->
            val role =
                when {
                    // Guarantee a headliner: promote the first act when subtitle_2 marked them all as support.
                    !hasHeadliner && index == 0 -> "HEADLINER"

                    supportFlags[index] -> "SUPPORT"

                    else -> "HEADLINER"
                }
            ScrapedArtist(name = name, role = role)
        }
    }
}
