package de.norm.events.artist

import de.norm.events.EVENTS_SCHEMA
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactive.awaitSingle
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository

/**
 * The slugs an artist had before a rename (#3289). The import resolves a scraped name through them, so
 * a rename survives the next import of an event that still bills the old name. A class, not a derived
 * query, because `ArtistRepository` is at detekt's function cap.
 */
@Repository
class ArtistSlugAliasStore(
    private val databaseClient: DatabaseClient
) {
    /** The artist id each of [slugs] is an alias of; a slug that is no alias is absent. */
    suspend fun artistIdsBySlug(slugs: Collection<String>): Map<String, Long> =
        if (slugs.isEmpty()) {
            emptyMap()
        } else {
            databaseClient
                .sql("SELECT slug, artist_id FROM $EVENTS_SCHEMA.artist_slug_alias WHERE slug IN (:slugs)")
                .bind("slugs", slugs)
                .map { row, _ -> requireNotNull(row.get("slug", String::class.java)) to requireNotNull(row.get("artist_id", Number::class.java)).toLong() }
                .all()
                .asFlow()
                .toList()
                .toMap()
        }

    /** The artist [slug] is an alias of, or null. */
    suspend fun artistIdOf(slug: String): Long? = artistIdsBySlug(listOf(slug))[slug]

    /** Records [slug] as a former slug of artist [artistId]. A slug already recorded keeps its artist. */
    suspend fun add(
        slug: String,
        artistId: Long
    ): Boolean =
        databaseClient
            .sql("INSERT INTO $EVENTS_SCHEMA.artist_slug_alias (slug, artist_id) VALUES (:slug, :artistId) ON CONFLICT (slug) DO NOTHING")
            .bind("slug", slug)
            .bind("artistId", artistId)
            .fetch()
            .rowsUpdated()
            .awaitSingle() > 0

    /** Removes [slug] from the aliases of artist [artistId], as when the artist takes that slug back. */
    suspend fun remove(
        slug: String,
        artistId: Long
    ): Boolean =
        databaseClient
            .sql("DELETE FROM $EVENTS_SCHEMA.artist_slug_alias WHERE slug = :slug AND artist_id = :artistId")
            .bind("slug", slug)
            .bind("artistId", artistId)
            .fetch()
            .rowsUpdated()
            .awaitSingle() > 0
}
