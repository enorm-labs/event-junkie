package de.norm.events.scraper.baiz

import de.norm.events.event.EventType
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.ApiClient
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.VenueLimitations
import de.norm.events.scraper.inferVenueFormatType
import de.norm.events.scraper.radar.RadarEvent
import de.norm.events.scraper.radar.RadarGroupImporter
import de.norm.events.scraper.radar.isRadarProgramme
import org.springframework.stereotype.Component

/** Radar categories Baiz files its stage nights under, beside radar's concert and party. */
private val STAGE_CATEGORIES = mapOf("theater" to EventType.SHOW.name, "discussion/presentation" to EventType.READING.name)

/** An uncategorised row that is no stage night: the tenants' advice hour, the chess afternoon, the knife and sewing service. */
private val NOT_PROGRAMME = Regex("""beratung|schach|spiele-?nachmittag|workshop|messer|nähen""", RegexOption.IGNORE_CASE)

/** The house's own words for its formats, read from an uncategorised title. */
private val BAIZ_FORMATS =
    linkedMapOf(
        "reformbühne" to EventType.READING.name,
        "lesebühne" to EventType.READING.name,
        "kunstbühne" to EventType.READING.name,
        "vortrag" to EventType.READING.name,
        "buchvorstellung" to EventType.READING.name,
        "flamenco" to EventType.SHOW.name
    )

/**
 * Baiz's programme, from its radar.squat.net group (node 1609). The pub's own programme page is
 * one month of plain text, and radar carries the same nights with a date and a time each.
 *
 * Baiz is a reading and talk stage first, so radar's concert-and-party filter is widened: rows
 * filed under `theater` or `discussion/presentation` are kept, and so is a row with no category,
 * which is how the pub posts its readings, the Sunday Reformbühne and the karaoke night. The
 * tenants' advice hour, the chess afternoon and the knife-sharpening Sundays are uncategorised too,
 * and are dropped by title.
 * An uncategorised row is typed by the title's format word, else `OTHER`.
 */
@Component
class BaizWebsiteImporter(
    apiClient: ApiClient
) : RadarGroupImporter(apiClient) {
    override val eventSource: EventSource = EventSource.BAIZ
    override val venueName: String = "Baiz"

    override fun isProgramme(event: RadarEvent): Boolean =
        isRadarProgramme(event) ||
            event.categories.any { it in STAGE_CATEGORIES } ||
            (event.categories.isEmpty() && !NOT_PROGRAMME.containsMatchIn(event.title))

    override fun eventType(event: RadarEvent): String =
        if (isRadarProgramme(event)) {
            event.eventType
        } else {
            event.categories.firstNotNullOfOrNull { STAGE_CATEGORIES[it] } ?: inferVenueFormatType(event.title, null, BAIZ_FORMATS)
        }
}

val BAIZ_LIMITATIONS =
    VenueLimitations(
        EventSource.BAIZ,
        AcceptedLimitation(LimitedAspect.ARTISTS, "the acts and readers are named only in free prose of no fixed shape"),
        AcceptedLimitation(LimitedAspect.GENRE, "radar files the nights under concert or no category, and the style is in prose"),
        AcceptedLimitation(LimitedAspect.IMAGE, "radar serves its poster files behind an anti-bot wall, which we do not pass"),
        AcceptedLimitation(LimitedAspect.TICKET_URL, "the pub sells no tickets"),
        AcceptedLimitation(LimitedAspect.DOORS_TIME, "radar gives one time per night"),
        AcceptedLimitation(LimitedAspect.PRICE, "radar lists no price for the pub's nights"),
        AcceptedLimitation(LimitedAspect.PROMOTERS, "radar names no promoter")
    )
