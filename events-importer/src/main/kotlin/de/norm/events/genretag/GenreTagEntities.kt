package de.norm.events.genretag

import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

/**
 * R2DBC entity mapped to the `genre_tag` table.
 *
 * Represents a normalized genre label used for structured event filtering.
 * Kept separate from the core [GenreTag] domain class so that `events-core`
 * remains free of Spring Data annotations.
 */
@Table("genre_tag")
data class GenreTagEntity(
    @Id val id: Long? = null,
    val name: String,
    val slug: String,
    /** A [GenreFamily] slug, or `null`; written by [GenreFamilyReconciler] and the tag insert. */
    val family: String? = null,
    @CreatedDate val createdAt: Instant? = null,
    @LastModifiedDate val updatedAt: Instant? = null
) {
    fun toDomain(): GenreTag =
        GenreTag(
            id = id,
            name = name,
            slug = slug,
            family = GenreFamily.fromSlug(family),
            createdAt = createdAt,
            updatedAt = updatedAt
        )

    companion object {
        fun fromDomain(genreTag: GenreTag): GenreTagEntity =
            GenreTagEntity(
                id = genreTag.id,
                name = genreTag.name,
                slug = genreTag.slug,
                family = genreTag.family?.slug,
                createdAt = genreTag.createdAt,
                updatedAt = genreTag.updatedAt
            )
    }
}

/**
 * R2DBC entity mapped to the `event_genre_tag` join table.
 *
 * Uses a surrogate auto-generated `id` column so that Spring Data R2DBC
 * correctly detects new entities (id = null → INSERT). A unique constraint
 * on (event_id, genre_tag_id) preserves the logical composite key.
 */
@Table("event_genre_tag")
data class EventGenreTagEntity(
    @Id val id: Long? = null,
    val eventId: Long,
    val genreTagId: Long,
    /** A [GenreTagOrigin] name. */
    val origin: String = GenreTagOrigin.SOURCE.name
)
