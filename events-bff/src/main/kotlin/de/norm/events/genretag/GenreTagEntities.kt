package de.norm.events.genretag

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

/**
 * Read-only R2DBC entity mapped to the `genre_tag` table.
 *
 * Normalized genre label used for structured filtering. Owned/written by the importer.
 */
@Table("genre_tag")
data class GenreTagEntity(
    @Id val id: Long? = null,
    val name: String,
    val slug: String,
    /** A `GenreFamily` slug, or `null` when the importer places the tag in no family. */
    val family: String? = null,
    val createdAt: Instant? = null,
    val updatedAt: Instant? = null
)

/**
 * Read-only R2DBC entity mapped to the `event_genre_tag` join table.
 */
@Table("event_genre_tag")
data class EventGenreTagEntity(
    @Id val id: Long? = null,
    val eventId: Long,
    val genreTagId: Long,
    /** A `GenreTagOrigin` name. */
    val origin: String = "SOURCE"
)
