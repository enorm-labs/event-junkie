package de.norm.events.scraper.binuu

import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.UNRESOLVED_EVENT_DATE
import de.norm.events.scraper.isNonArtistName
import de.norm.events.scraper.stringOrNull
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import tools.jackson.databind.JsonNode

/**
 * Pure parser for Bi Nuu detail pages (`/de/events/<id>`), the primary data source: the
 * embedded `data.item` payload ([BinuuSvelteKitPayload]) is a superset of the overview entry,
 * adding doors time, description (`text`), ticket URL, promoters and the `performers` roster.
 * Roles come from the structured data: every performer is kept, tagged `SUPPORT` when it appears
 * in the `subtitle_2` support/guest line, else `HEADLINER`; if every performer is support, the
 * first is promoted.
 *
 * @see BinuuOverviewPageScraper for overview parsing (discovery, fallback).
 * @see BinuuWebsiteImporter for the HTTP fetch orchestrator.
 * @see <a href="https://binuu.de/de/events/inzpqdgvi1eab2q">Example detail page</a>
 */
class BinuuDetailPageScraper {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses a detail page into a [ScrapedEvent], or `null` without a parseable `item` payload or
     * the required id/title.
     *
     * @param sourceUrl the event's URL, [ScrapedEvent.sourceUrl] and the [ScrapedEvent.sourceId]
     * confirmation.
     */
    @Suppress("ReturnCount") // Guard clauses for missing payload, id, and title are clearer than nesting
    fun scrape(
        document: Document,
        sourceUrl: String
    ): ScrapedEvent? {
        val item = BinuuSvelteKitPayload.dataNode(document, "item", '{')
        if (item == null || !item.isObject) {
            logger.warn { "Detail page has no Bi Nuu item payload, skipping" }
            return null
        }
        val id = item.stringOrNull("id") ?: item.stringOrNull("dbId")
        if (id == null) {
            logger.warn { "Detail page has no event id, skipping" }
            return null
        }
        val title = item.stringOrNull("title")
        if (title == null) {
            logger.warn { "Detail page has no event title, skipping" }
            return null
        }

        val start = item.stringOrNull("start")
        val subtitle = item.stringOrNull("subtitle")
        val description = parseDescription(item.stringOrNull("text"))
        return ScrapedEvent(
            title = title,
            subtitle = subtitle,
            description = description,
            // No category field, so the type is inferred from title/subtitle (inferBinuuEventType).
            eventType = inferBinuuEventType(title, subtitle),
            // Detail pages carry the real date; the sentinel only if absent, then fillGapsFromOverview.
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
            promoters = parsePromoters(item),
            promoterWebsites = parsePromoterWebsites(item)
        )
    }

    /**
     * The HTML `text` blurb as plain text, paragraphs joined by newlines, falling back to flattened
     * text without `<p>`. `null` when empty.
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
     * The first ticket URL from `tickets[]`, stray whitespace stripped (a rogue space after `?`).
     * `null` without an absolute URL; some events sell only via a link in the description.
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

    /** The `url` beside each promoter's `title`, where the venue links one (#1319). */
    private fun parsePromoterWebsites(item: JsonNode): Map<String, String> =
        item
            .path("promoters")
            .mapNotNull { promoter ->
                val title = promoter.stringOrNull("title") ?: return@mapNotNull null
                val url = promoter.stringOrNull("url")?.takeIf { it.startsWith("http") } ?: return@mapNotNull null
                title to url
            }.toMap()

    /**
     * The roster from `performers`, `SUPPORT` when the name appears in `subtitle_2`, else
     * `HEADLINER`; non-artist names dropped; the first promoted if every one was support.
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
