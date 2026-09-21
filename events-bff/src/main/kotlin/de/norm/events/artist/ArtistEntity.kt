package de.norm.events.artist

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

/**
 * Read-only R2DBC entity mapped to the `artist` table.
 *
 * Lean projection for the BFF's read paths. The table is owned and written by the importer.
 */
@Table("artist")
data class ArtistEntity(
    @Id val id: Long? = null,
    val name: String,
    val slug: String,
    val description: String? = null,
    val imageUrl: String? = null,
    val imageAttribution: String? = null,
    val imageLicenceId: String? = null,
    val imageSourceUrl: String? = null,
    val websiteUrl: String? = null,
    val facebookUrl: String? = null,
    val instagramUrl: String? = null,
    val youtubeUrl: String? = null,
    val bandcampUrl: String? = null,
    val soundcloudUrl: String? = null,
    val discogsUrl: String? = null,
    val wikidataUrl: String? = null,
    val residentAdvisorUrl: String? = null,
    val spotifyUrl: String? = null,
    /** [ArtistType] by name, or null until the importer read the entity (ADR-031, step C). */
    val artistType: String? = null,
    val musicbrainzId: String? = null,
    val musicbrainzMatch: String = MusicBrainzMatch.UNCHECKED.name,
    val musicbrainzCheckedAt: Instant? = null,
    val createdAt: Instant? = null,
    val updatedAt: Instant? = null
)
