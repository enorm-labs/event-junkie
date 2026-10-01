package de.norm.events.scraper.humboldthain

import de.norm.events.event.ArtistRole
import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.blankToNull
import de.norm.events.scraper.elfsight.ElfsightAction
import de.norm.events.scraper.elfsight.ElfsightEventNode
import de.norm.events.scraper.elfsight.OCCURRENCE_HORIZON_WEEKS
import de.norm.events.scraper.elfsight.elfsightActionUrl
import de.norm.events.scraper.elfsight.elfsightJsonMapper
import de.norm.events.scraper.elfsight.elfsightOccurrenceDates
import de.norm.events.scraper.elfsight.parseElfsightDate
import de.norm.events.scraper.elfsight.parseElfsightEventNodes
import de.norm.events.scraper.headlinersFromTitle
import de.norm.events.scraper.htmlParagraphText
import de.norm.events.scraper.humboldthain.HumboldthainApiScraper.Companion.TICKET_URL_PATTERN
import de.norm.events.scraper.isNonArtistName
import de.norm.events.scraper.parseTime
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.LocalDate

/** Public landing page every event links back to — the widget exposes no per-event URLs. */
private const val HUMBOLDTHAIN_URL = "https://www.humboldthain.com/"

/**
 * Pure parser for Humboldthain Club's programme, from the JSON boot response of the Elfsight
 * "Event Calendar" widget embedded on its WordPress landing page.
 *
 * The widget renders client-side, so the page carries no events; its boot API returns the
 * calendar as JSON (ADR-007 §"Selector Strategy" priority 1). The payload shape and readers are
 * shared with the other Elfsight venue — see [de.norm.events.scraper.elfsight.ElfsightEventNode].
 *
 * **Recurrences are expanded.** The resident night is a *single* entry with a weekly repeat
 * rule the widget expands in the browser, so reading only `start.date` would import it once at
 * the series' long-past opening date and lose every upcoming occurrence. Weekly rules become one
 * event per occurrence over a rolling [OCCURRENCE_HORIZON_WEEKS] horizon, bounded further by the
 * rule's own end date or count ([elfsightOccurrenceDates]), which is why `sourceId` combines the
 * widget id with the occurrence date.
 *
 * **Artists come from the description's links, not its prose.** The roster is `ra.co/dj/<slug>`
 * anchors whose text is the DJ's name; the prose around them varies ("Lineup/Musik", "Line-up
 * Live:") and its other lines are door policy and awareness notes.
 *
 * **Every night is a party** unless the title opens with the one category marker, `KONZERT:`,
 * which is stripped and makes the remainder the headliner. The widget's `eventType` vocabulary
 * is ignored: the venue filled it with weekday labels ("Samstag, 14:00") that contradict
 * `start.time`.
 *
 * @see HUMBOLDTHAIN_LIMITATIONS for what the source does not publish.
 * @see HumboldthainWebsiteImporter for the HTTP fetch orchestrator.
 */
class HumboldthainApiScraper(
    private val clock: Clock = Clock.systemDefaultZone()
) {
    private val logger = KotlinLogging.logger {}

    private val jsonMapper: JsonMapper = elfsightJsonMapper()

    /**
     * Parses every event from the Elfsight widget boot response [json], expanding weekly
     * recurrences into one event per occurrence.
     *
     * @param json the raw JSON body of the `p/boot/?w=<widgetId>` response.
     * @return the [ScrapedEvent]s; empty if absent, unparseable or without events.
     */
    fun scrape(json: String): List<ScrapedEvent> {
        val eventNodes = parseElfsightEventNodes(jsonMapper, json, VENUE_NAME) ?: return emptyList()
        logger.info { "Found ${eventNodes.size} calendar entry/entries in Humboldthain widget response" }

        @Suppress("TooGenericExceptionCaught") // Intentional: skip individual malformed events without aborting the import.
        val parsed =
            eventNodes.flatMap { node ->
                try {
                    parseEvent(jsonMapper.treeToValue(node, ElfsightEventNode::class.java))
                } catch (e: Exception) {
                    logger.warn(e) { "Failed to parse Humboldthain event, skipping" }
                    emptyList()
                }
            }

        // A series whose horizon overlaps a one-off entry of the same id would collide on sourceId.
        return parsed.distinctBy { it.sourceId }
    }

    /** Validates one calendar entry and expands it into one [ScrapedEvent] per occurrence date. */
    @Suppress("ReturnCount") // Guard clauses for the required id, title, and date are clearer than nesting.
    private fun parseEvent(node: ElfsightEventNode): List<ScrapedEvent> {
        val id = node.id.blankToNull()
        if (id == null) {
            logger.warn { "Humboldthain event has no id, skipping" }
            return emptyList()
        }

        val rawTitle = node.name.blankToNull()
        if (rawTitle == null) {
            logger.warn { "Humboldthain event '$id' has no name, skipping" }
            return emptyList()
        }

        val seriesStart = parseElfsightDate(node.start?.date)
        if (seriesStart == null) {
            logger.warn { "Humboldthain event '$id' has no parseable date, skipping" }
            return emptyList()
        }

        val concert = CONCERT_TITLE_PREFIX.containsMatchIn(rawTitle)
        val title = if (concert) rawTitle.replaceFirst(CONCERT_TITLE_PREFIX, "").trim().ifBlank { rawTitle } else rawTitle
        val descriptionHtml = node.description.blankToNull()
        val description = descriptionHtml?.let { Jsoup.parse(it) }

        // A "KONZERT:" night bills its act in the title; every other night is a DJ party whose
        // roster, if announced, is the description's Resident Advisor artist links.
        val artists = (if (concert) headlinersFromTitle(title) else emptyList()) + djArtists(description)
        val descriptionText = htmlParagraphText(descriptionHtml)

        return elfsightOccurrenceDates(node, seriesStart, LocalDate.now(clock), VENUE_NAME, id).map { date ->
            ScrapedEvent(
                title = title,
                description = descriptionText,
                eventType = if (concert) EventType.CONCERT.name else EventType.PARTY.name,
                eventDate = date,
                // All-day entries carry a placeholder time; only a real clock value becomes a start time.
                startTime = if (node.isAllDay) null else parseTime(node.start?.time.blankToNull()),
                imageUrl =
                    node.coverImage
                        ?.url
                        .blankToNull()
                        ?.takeIf { it.startsWith("http") },
                sourceUrl = HUMBOLDTHAIN_URL,
                sourceId = "${EventSource.HUMBOLDTHAIN.sourceIdPrefix}$id-$date",
                ticketUrl = ticketUrl(node.actions, description),
                artists = artists
            )
        }
    }

    /**
     * The DJs billed on a night: the link text of every `ra.co/dj/<slug>` anchor in the
     * description, in document order, de-duplicated case-insensitively and filtered through the
     * shared [isNonArtistName]. Resident Advisor *event* links in the same prose are ticket shops,
     * not performers, matched by [ticketUrl] instead.
     */
    private fun djArtists(description: Document?): List<ScrapedArtist> =
        description
            ?.select(RA_ARTIST_LINK_SELECTOR)
            ?.map { it.text().trim() }
            ?.filter { it.isNotBlank() && !isNonArtistName(it) }
            ?.distinctBy { it.lowercase() }
            ?.map { ScrapedArtist(name = it, role = ArtistRole.DJ.name) }
            .orEmpty()

    /**
     * The night's ticket-shop link: the widget's own "Presale Tickets" action when present,
     * otherwise a shop link written into the prose (a Resident Advisor event page or an Eventim
     * listing). A `ra.co/dj/` link is an artist profile, excluded by requiring [TICKET_URL_PATTERN].
     */
    private fun ticketUrl(
        actions: List<ElfsightAction>,
        description: Document?
    ): String? =
        elfsightActionUrl(actions)
            ?: description
                ?.select("a[href]")
                ?.map { it.attr("href").trim() }
                ?.firstOrNull { it.startsWith("http") && TICKET_URL_PATTERN.containsMatchIn(it) }

    companion object {
        /** Names the venue in the shared payload reader's warnings. */
        private const val VENUE_NAME = "Humboldthain"

        /** The venue's one category marker, opening a title it wants read as a concert rather than a party. */
        private val CONCERT_TITLE_PREFIX = Regex("""^konzert\s*[:\-–—]\s*""", RegexOption.IGNORE_CASE)

        /** Resident Advisor **artist** profiles — the venue's machine-readable lineup markup. */
        private const val RA_ARTIST_LINK_SELECTOR = "a[href*=ra.co/dj/]"

        /** Ticket shops the venue links from its prose: a Resident Advisor event page, Eventim, or any "ticket" URL. */
        private val TICKET_URL_PATTERN = Regex("""ra\.co/events/|eventim|dice\.fm|ticket""", RegexOption.IGNORE_CASE)
    }
}
