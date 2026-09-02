package de.norm.events.genretag

import java.time.Instant

/**
 * Represents a normalized music genre tag used for filtering and categorizing events.
 *
 * Genre tags are canonical labels derived from the free-text genre strings scraped
 * from venue websites. Multiple events can share the same genre tag, and a single
 * event can have multiple genre tags (many-to-many via `event_genre_tag`).
 *
 * The raw genre text is preserved on the event for display, while these normalized
 * tags enable structured filtering in the frontend.
 */
data class GenreTag(
    /** Database primary key, `null` before persistence. */
    val id: Long? = null,
    /** Canonical display name. */
    val name: String,
    /** URL-friendly identifier, derived from the name. Example: `"hip-hop"` */
    val slug: String,
    /** Timestamp when this record was first created. Set by the database. */
    val createdAt: Instant? = null,
    /** Timestamp when this record was last modified. Set by the database. */
    val updatedAt: Instant? = null
)
