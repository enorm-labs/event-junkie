package de.norm.events.scraper.thecloud

import de.norm.events.event.EventType
import de.norm.events.scraper.DOORS_LABELS
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.START_LABELS
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.TecPage
import de.norm.events.scraper.cleanEventTitle
import de.norm.events.scraper.inferUnmarkedTitleType
import de.norm.events.scraper.labelledClock
import de.norm.events.scraper.parseTecPage
import de.norm.events.scraper.stringOrNull
import de.norm.events.scraper.tecDateTime
import de.norm.events.scraper.tecDescription
import de.norm.events.scraper.tecImageUrl
import de.norm.events.scraper.tecOrganizerNames
import de.norm.events.scraper.tecText
import io.github.oshai.kotlinlogging.KotlinLogging
import tools.jackson.databind.JsonNode

/**
 * Pure JSON parser for The Cloud's **The Events Calendar** REST API; the page shape and the field readers are [parseTecPage]'s.
 *
 * - **Every event is filed under `Night Club`**, so a night is a party unless its title bills a live show
 *   (`LIVE ON STAGE: …`, `… LIVE IN BERLIN`), which is a concert of the act it names.
 * - **The API time can be the doors**: when the prose prints `Einlass` at that time, the start is the `Start` or `Event` clock.
 * - **`website` is the promoter's ticket link**, copied from social media with `fbclid` and `utm_*` attached; those go.
 */
class TheCloudApiScraper {
    private val logger = KotlinLogging.logger {}

    fun scrapePage(json: String): TecPage = parseTecPage(json, EventSource.THE_CLOUD, ::toScrapedEvent)

    private fun toScrapedEvent(event: JsonNode): ScrapedEvent? {
        val slug = event.stringOrNull("slug") ?: return null
        val start = event.tecDateTime("start_date") ?: return null
        val title = event.tecText("title")?.let(::cleanEventTitle)
        if (title.isNullOrBlank()) {
            logger.warn { "The Cloud event '$slug' has no title, skipping" }
            return null
        }
        val description = event.tecDescription()
        val doors = labelledClock(description, DOORS_LABELS)?.takeIf { it == start.toLocalTime() }
        val end = event.tecDateTime("end_date")?.takeIf { it.isAfter(start) }
        val liveAct = if (LIVE_SHOW.containsMatchIn(title)) liveAct(title) else null

        return ScrapedEvent(
            title = title,
            description = description,
            eventType = if (liveAct != null) EventType.CONCERT.name else partyUnlessTitled(title),
            eventDate = start.toLocalDate(),
            doorsTime = doors,
            startTime = if (doors != null) labelledClock(description, EVENT_START_LABELS) else start.toLocalTime(),
            endDate = end?.toLocalDate(),
            endTime = end?.toLocalTime(),
            imageUrl = event.tecImageUrl(),
            sourceUrl = event.stringOrNull("url") ?: return null,
            sourceId = "${EventSource.THE_CLOUD.sourceIdPrefix}$slug",
            ticketUrl = event.tecText("website")?.let(::withoutTracking),
            artists = listOfNotNull(liveAct?.let { ScrapedArtist(name = it) }),
            promoters = event.tecOrganizerNames()
        )
    }

    /** The type a title cue names, or a party: every event is filed as a club night. */
    private fun partyUnlessTitled(title: String): String = inferUnmarkedTitleType(title).takeUnless { it == EventType.OTHER.name } ?: EventType.PARTY.name

    /** The act a live-show title bills, or null when the title names none. */
    private fun liveAct(title: String): String? =
        LIVE_ACT_PATTERNS
            .firstNotNullOfOrNull {
                it
                    .find(title)
                    ?.groupValues
                    ?.get(1)
                    ?.trim()
            }?.takeIf { it.isNotEmpty() }

    /** [url] without its social-media tracking parameters. */
    private fun withoutTracking(url: String): String {
        val stripped = url.replace(TRACKING_PARAMETER, "")
        return if ('?' in stripped) stripped else stripped.replaceFirst('&', '?')
    }

    private companion object {
        val LIVE_SHOW = Regex("""\bLIVE\s+(?:ON\s+STAGE|IN\s+BERLIN)\b""", RegexOption.IGNORE_CASE)

        /** `LIVE ON STAGE: ВУИНДЛИЯ`, `NAIROBI NIGHTS PRÄSENTIERT RAYVANNY — LIVE IN BERLIN`, `ENCA LIVE ON STAGE ★ CLUB …`. */
        val LIVE_ACT_PATTERNS =
            listOf(
                Regex("""^LIVE\s+ON\s+STAGE\s*:\s*(.+)$""", RegexOption.IGNORE_CASE),
                Regex("""^(?:.+?\bPRÄSENTIERT\s+)?(.+?)\s*[—–-]?\s+LIVE\s+(?:ON\s+STAGE|IN\s+BERLIN)\b""", RegexOption.IGNORE_CASE)
            )

        /** The words before the show's own clock when the API time is the doors: `START: 23:00`, `Event: 19:00–22:00`. */
        const val EVENT_START_LABELS = "$START_LABELS|event"

        val TRACKING_PARAMETER = Regex("""[?&](?:fbclid|utm_[a-z]+)=[^&#]*""")
    }
}
