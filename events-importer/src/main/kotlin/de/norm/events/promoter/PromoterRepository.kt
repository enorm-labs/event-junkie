package de.norm.events.promoter

import de.norm.events.EVENTS_SCHEMA
import kotlinx.coroutines.flow.Flow
import org.springframework.data.domain.Pageable
import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository

/**
 * Reactive repository for [Promoter][de.norm.events.promoter.Promoter] persistence via R2DBC.
 */
interface PromoterRepository : CoroutineCrudRepository<PromoterEntity, Long> {
    suspend fun findBySlug(slug: String): PromoterEntity?

    /** Batch-fetches promoters by their slugs. Used by the import pipeline to avoid N+1 queries. */
    fun findBySlugIn(slugs: Collection<String>): Flow<PromoterEntity>

    /** Finds all promoters with pagination and sorting applied via [pageable]. */
    fun findAllBy(pageable: Pageable): Flow<PromoterEntity>

    /**
     * Inserts a promoter only if its [slug] is not already taken, returning the number of rows
     * inserted (`1` if created, `0` if it already existed).
     *
     * `ON CONFLICT DO NOTHING` makes a duplicate slug a no-op instead of raising, so a concurrent
     * import inserting the same promoter first does not abort the caller's transaction.
     * `created_at`/`updated_at` fall back to their `DEFAULT now()`.
     */
    @Modifying
    @Query("INSERT INTO $EVENTS_SCHEMA.promoter (name, slug) VALUES (:name, :slug) ON CONFLICT (slug) DO NOTHING")
    suspend fun insertIfAbsent(
        name: String,
        slug: String
    ): Int
}
