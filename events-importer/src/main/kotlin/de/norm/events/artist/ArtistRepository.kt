package de.norm.events.artist

import de.norm.events.EVENTS_SCHEMA
import kotlinx.coroutines.flow.Flow
import org.springframework.data.domain.Pageable
import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository

/**
 * Reactive repository for [Artist][de.norm.events.artist.Artist] persistence via R2DBC.
 */
interface ArtistRepository : CoroutineCrudRepository<ArtistEntity, Long> {
    /** Finds all artists with pagination and sorting applied via [pageable]. */
    fun findAllBy(pageable: Pageable): Flow<ArtistEntity>

    /** Finds a single artist by its unique slug, or null if not found. */
    suspend fun findBySlug(slug: String): ArtistEntity?

    /** Batch-fetches artists by their slugs. Used by the import pipeline to avoid N+1 queries. */
    fun findBySlugIn(slugs: Collection<String>): Flow<ArtistEntity>

    /**
     * Inserts an artist only if its [slug] is not already taken, returning the number of rows
     * inserted (`1` if created, `0` if it already existed).
     *
     * `ON CONFLICT DO NOTHING` makes this a no-op instead of raising on a duplicate slug, so a
     * concurrent import that inserts the same artist first does **not** abort the caller's
     * transaction — the Postgres pitfall that a caught unique-violation still poisons the
     * surrounding transaction. `created_at`/`updated_at` fall back to their `DEFAULT now()`.
     */
    @Modifying
    @Query("INSERT INTO $EVENTS_SCHEMA.artist (name, slug) VALUES (:name, :slug) ON CONFLICT (slug) DO NOTHING")
    suspend fun insertIfAbsent(
        name: String,
        slug: String
    ): Int
}
