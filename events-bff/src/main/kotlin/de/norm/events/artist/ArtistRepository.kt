package de.norm.events.artist

import de.norm.events.EVENTS_SCHEMA
import kotlinx.coroutines.flow.Flow
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository

interface ArtistRepository : CoroutineCrudRepository<ArtistEntity, Long> {
    /** Finds a single artist by its unique slug, or null if not found. */
    suspend fun findBySlug(slug: String): ArtistEntity?

    /** Finds the artist that [slug] is an alias of: the slug it had before a rename, or a merged artist's. */
    @Query(
        "SELECT a.* FROM $EVENTS_SCHEMA.artist a " +
            "JOIN $EVENTS_SCHEMA.artist_slug_alias alias ON alias.artist_id = a.id WHERE alias.slug = :slug"
    )
    suspend fun findByAliasSlug(slug: String): ArtistEntity?

    /** Batch-fetches artists by ID — used to resolve event lineups for an event page. */
    fun findByIdIn(ids: Collection<Long>): Flow<ArtistEntity>
}
