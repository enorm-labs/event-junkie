package de.norm.events.importing

import de.norm.events.artist.ArtistOccupationRepository
import de.norm.events.artist.artistSlugFor
import de.norm.events.event.ArtistRole
import de.norm.events.event.EventRepository
import de.norm.events.event.EventType
import de.norm.events.scraper.BERLIN
import de.norm.events.scraper.ScrapedEvent
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.toSet
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.LocalDate

/**
 * Types a night `COMEDY` when the venue gave no cue and a headliner is a comedian (ADR-039, #2314).
 *
 * Reads stored data only: `artist.comedian`, which [ArtistOccupationService] fills from Wikidata on
 * the artist lookup tick. The import applies the rule as it writes ([retype]), so a re-import does
 * not flip the row back. The tick applies it to stored rows ([retypeStored]), for an artist read
 * after its nights were imported.
 */
@Service
class PerformerTyping(
    private val occupations: ArtistOccupationRepository,
    private val eventRepository: EventRepository,
    private val clock: Clock = Clock.system(BERLIN)
) {
    private val logger = KotlinLogging.logger {}

    /** [events] with each fallback-typed night whose headliner is a stored comedian typed `COMEDY`. */
    suspend fun retype(events: List<ScrapedEvent>): List<ScrapedEvent> {
        val open = events.filter { it.typeIsFallback && it.eventType != EventType.COMEDY.name }
        val openIds = open.mapTo(mutableSetOf()) { it.sourceId }
        val slugs = open.flatMap { headlinerSlugs(it) }.toSet()
        val comedians = if (slugs.isEmpty()) emptySet() else occupations.findComedianSlugs(slugs).toSet()
        if (comedians.isEmpty()) return events
        var retyped = 0
        val result =
            events.map { event ->
                if (event.sourceId in openIds && headlinerSlugs(event).any { it in comedians }) {
                    retyped++
                    event.copy(eventType = EventType.COMEDY.name)
                } else {
                    event
                }
            }
        logger.info { "Typed $retyped night(s) COMEDY by a comedian headliner" }
        return result
    }

    /** One pass over the stored nights not yet over; the number of rows typed `COMEDY`. */
    suspend fun retypeStored(): Int = eventRepository.retypeComedianNights(LocalDate.now(clock))

    private fun headlinerSlugs(event: ScrapedEvent): List<String> =
        event.artists
            .filter { it.role == ArtistRole.HEADLINER.name }
            .map { artistSlugFor(it.name) }
            .filter { it.isNotEmpty() }

    companion object {
        /** Wikidata items for comedian, stand-up comedian and cabaret performer (Kabarettist). */
        val COMEDY_OCCUPATIONS = setOf("Q245068", "Q18545066", "Q15214752")
    }
}
