package de.norm.events.scraper.baiz

import de.norm.events.event.ArtistRole
import de.norm.events.event.EventType
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.ApiClient
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.VenueLimitations
import de.norm.events.scraper.inferVenueFormatType
import de.norm.events.scraper.isNonArtistName
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
 * A concert title's act after ` mit ` and an article: `Solikonzert mit dem Olga Benario Chor`, `Liederabend mit Tobias Thiele
 * zwischen Havanna und Berlin`. The name ends at ` zwischen `, a spaced dash, a colon or the end.
 */
private val CONCERT_WITH_ACT = Regex(""" mit (?:(?:dem|der|den) )?(.+?)(?: zwischen | [-–] |:|$)""")

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
 *
 * Radar has no performer field, and the acts are named in prose. A concert title that bills its act after ` mit ` is
 * the one exception, read by [CONCERT_WITH_ACT] (events 15179, 15185). `Roda de choro` and
 * `Konzert DUO AQUAMARINE AVE MARIS STELLA` bill no one.
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

    override fun lineup(event: RadarEvent): Lineup {
        if (eventType(event) != EventType.CONCERT.name) return Lineup()
        val act =
            CONCERT_WITH_ACT
                .find(event.title.trim())
                ?.groupValues
                ?.get(1)
                ?.trim()
                ?.takeUnless { it.isBlank() || isNonArtistName(it) }
        return Lineup(artists = listOfNotNull(act?.let { ScrapedArtist(name = it, role = ArtistRole.HEADLINER) }))
    }

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
        AcceptedLimitation(
            LimitedAspect.ARTISTS,
            "the acts and readers are named only in free prose of no fixed shape, except the act a concert title names after mit",
            verified = "2026-11-23"
        ),
        AcceptedLimitation(LimitedAspect.GENRE, "radar files the nights under concert or no category, and the style is in prose", verified = "2026-10-16"),
        AcceptedLimitation(LimitedAspect.IMAGE, "radar serves its poster files behind an anti-bot wall, which we do not pass", verified = "2026-10-16"),
        AcceptedLimitation(LimitedAspect.TICKET_URL, "the pub sells no tickets", verified = "2026-10-16"),
        AcceptedLimitation(LimitedAspect.DOORS_TIME, "radar gives one time per night", verified = "2026-10-16"),
        AcceptedLimitation(LimitedAspect.PRICE, "radar lists no price for the pub's nights", verified = "2026-10-16"),
        AcceptedLimitation(LimitedAspect.PROMOTERS, "radar names no promoter", verified = "2026-10-16")
    )
