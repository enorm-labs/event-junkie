package de.norm.events.importing

import de.norm.events.EVENTS_SCHEMA
import de.norm.events.event.EventEntity
import de.norm.events.event.EventType
import de.norm.events.genretag.EventGenreTagEntity
import de.norm.events.genretag.EventGenreTagRepository
import de.norm.events.genretag.GenreTagOrigin
import de.norm.events.genretag.GenreTagRepository
import de.norm.events.genretag.descriptionGenres
import de.norm.events.genretag.genreFamily
import de.norm.events.slug.SlugGenerator
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.awaitSingle
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Service

/**
 * Derives genre tags for the events whose source publishes none (ADR-047). Runs inside the import transaction, after the genre sync.
 *
 * A music event takes the source tags its billed acts carry on their other events, past or upcoming. A support act does not count.
 * An event still untagged takes the genre words of its description ([descriptionGenres]). A derived tag never feeds another derivation.
 */
@Service
class GenreDerivationService(
    private val databaseClient: DatabaseClient,
    private val eventGenreTagRepository: EventGenreTagRepository,
    private val genreTagRepository: GenreTagRepository
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Replaces the derived tags of [events] with what their acts and descriptions give today. An event with a source tag keeps no derived tag.
     * The caller leaves out an event whose genre tags are pinned.
     */
    suspend fun derive(events: List<EventEntity>): GenreDerivation {
        val ids = events.mapNotNull { it.id }
        if (ids.isEmpty()) return GenreDerivation()
        val stored = eventGenreTagRepository.findByEventIdIn(ids).toList().groupBy { it.eventId }
        val untagged = events.filter { event -> stored[event.id].orEmpty().none { it.origin == GenreTagOrigin.SOURCE.name } }
        val music = untagged.filter { EventType.parseOrDefault(it.eventType) in MUSIC_TYPES }.mapNotNull { it.id }.toSet()

        val byArtist = artistTagIds(music)
        val descriptionNames =
            untagged
                .filter { it.id in music && byArtist[it.id].isNullOrEmpty() }
                .mapNotNull { event -> descriptionGenres(event.description).takeIf { it.isNotEmpty() }?.let { requireNotNull(event.id) to it } }
                .toMap()
        val tagIdByName = resolveTagIds(descriptionNames.values.flatten().toSet())
        val desired: Map<Long, Map<Long, GenreTagOrigin>> =
            byArtist.mapValues { (_, tagIds) -> tagIds.associateWith { GenreTagOrigin.ARTIST } } +
                descriptionNames.mapValues { (_, names) -> names.mapNotNull { tagIdByName[it] }.associateWith { GenreTagOrigin.DESCRIPTION } }

        val derivedRows = stored.values.flatten().filter { it.origin != GenreTagOrigin.SOURCE.name }
        val stale = derivedRows.filter { row -> desired[row.eventId]?.get(row.genreTagId)?.name != row.origin }
        val kept = (derivedRows - stale.toSet()).map { it.eventId to it.genreTagId }.toSet()
        val toInsert =
            desired.flatMap { (eventId, tags) ->
                tags.filterKeys { (eventId to it) !in kept }.map { (tagId, origin) ->
                    EventGenreTagEntity(eventId = eventId, genreTagId = tagId, origin = origin.name)
                }
            }
        if (stale.isNotEmpty()) eventGenreTagRepository.deleteAllById(stale.mapNotNull { it.id })
        if (toInsert.isNotEmpty()) eventGenreTagRepository.saveAll(toInsert).toList()

        val outcome =
            GenreDerivation(
                fromArtists = byArtist.size,
                fromDescriptions = descriptionNames.count { (id, _) -> desired[id].orEmpty().isNotEmpty() },
                inserted = toInsert.size,
                removed = stale.size
            )
        if (outcome.inserted > 0 || outcome.removed > 0) {
            logger.info {
                "Derived genre tags for ${outcome.fromArtists} event(s) from their acts and ${outcome.fromDescriptions} from their description: " +
                    "${outcome.inserted} added, ${outcome.removed} removed"
            }
        }
        return outcome
    }

    /** The source tags the billed acts of each event carry elsewhere, most frequent first, at most [MAX_ARTIST_GENRES] per event. */
    private suspend fun artistTagIds(eventIds: Set<Long>): Map<Long, List<Long>> {
        if (eventIds.isEmpty()) return emptyMap()
        return databaseClient
            .sql(ARTIST_TAGS)
            .bind("ids", eventIds.toTypedArray())
            .map { row, _ -> row.long("event_id") to row.long("genre_tag_id") }
            .all()
            .collectList()
            .awaitSingle()
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, tagIds) -> tagIds.take(MAX_ARTIST_GENRES) }
    }

    /** The id of each canonical genre name, creating a tag the vocabulary names and the table lacks. */
    private suspend fun resolveTagIds(names: Set<String>): Map<String, Long> {
        if (names.isEmpty()) return emptyMap()
        val slugByName = names.associateWith { SlugGenerator.slugify(it) }
        val missing =
            slugByName.values.toSet() -
                genreTagRepository
                    .findBySlugIn(slugByName.values)
                    .toList()
                    .map { it.slug }
                    .toSet()
        slugByName.filterValues { it in missing }.forEach { (name, slug) -> genreTagRepository.insertIfAbsent(name, slug, genreFamily(slug)?.slug) }
        val idBySlug = genreTagRepository.findBySlugIn(slugByName.values).toList().associate { it.slug to it.id }
        return slugByName.mapNotNull { (name, slug) -> idBySlug[slug]?.let { name to it } }.toMap()
    }

    private companion object {
        val MUSIC_TYPES = setOf(EventType.CONCERT, EventType.PARTY, EventType.FESTIVAL)
        const val MAX_ARTIST_GENRES = 3

        /** Ties break on the tag name, so a re-import derives the same tags. */
        val ARTIST_TAGS =
            """
            SELECT ea.event_id, egt.genre_tag_id
            FROM $EVENTS_SCHEMA.event_artist ea
            JOIN $EVENTS_SCHEMA.event_artist other ON other.artist_id = ea.artist_id AND other.event_id <> ea.event_id
            JOIN $EVENTS_SCHEMA.event_genre_tag egt ON egt.event_id = other.event_id AND egt.origin = 'SOURCE'
            JOIN $EVENTS_SCHEMA.genre_tag gt ON gt.id = egt.genre_tag_id
            WHERE ea.event_id = ANY(:ids) AND ea.role <> 'SUPPORT'
            GROUP BY ea.event_id, egt.genre_tag_id, gt.name
            ORDER BY ea.event_id, count(DISTINCT other.event_id) DESC, gt.name
            """.trimIndent()
    }
}

private fun io.r2dbc.spi.Row.long(column: String): Long = requireNotNull(get(column, Long::class.javaObjectType)) { "$column is NOT NULL" }

/** What [GenreDerivationService.derive] did. */
data class GenreDerivation(
    /** Events that took tags from their acts. */
    val fromArtists: Int = 0,
    /** Events that took tags from their description. */
    val fromDescriptions: Int = 0,
    /** Derived rows written. */
    val inserted: Int = 0,
    /** Derived rows deleted: the event got a source tag, or the acts or the description changed. */
    val removed: Int = 0
)
