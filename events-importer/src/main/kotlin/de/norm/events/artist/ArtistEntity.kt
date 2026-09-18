package de.norm.events.artist

import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

/**
 * R2DBC entity mapped to the `artist` table.
 *
 * Kept separate from the core [Artist] domain class so that `events-core` remains
 * free of Spring Data annotations.
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
    val musicbrainzId: String? = null,
    /** [MusicBrainzMatch] by name; a String so R2DBC needs no converter, as `event.status` does it. */
    val musicbrainzMatch: String = MusicBrainzMatch.UNCHECKED.name,
    val musicbrainzCheckedAt: Instant? = null,
    @CreatedDate val createdAt: Instant? = null,
    @LastModifiedDate val updatedAt: Instant? = null
) {
    fun toDomain(): Artist =
        Artist(
            id = id,
            name = name,
            slug = slug,
            description = description,
            imageUrl = imageUrl,
            imageAttribution = imageAttribution,
            imageLicenceId = imageLicenceId,
            imageSourceUrl = imageSourceUrl,
            websiteUrl = websiteUrl,
            facebookUrl = facebookUrl,
            instagramUrl = instagramUrl,
            youtubeUrl = youtubeUrl,
            musicbrainzId = musicbrainzId,
            musicbrainzMatch = MusicBrainzMatch.valueOf(musicbrainzMatch),
            musicbrainzCheckedAt = musicbrainzCheckedAt,
            createdAt = createdAt,
            updatedAt = updatedAt
        )

    companion object {
        fun fromDomain(artist: Artist): ArtistEntity =
            ArtistEntity(
                id = artist.id,
                name = artist.name,
                slug = artist.slug,
                description = artist.description,
                imageUrl = artist.imageUrl,
                imageAttribution = artist.imageAttribution,
                imageLicenceId = artist.imageLicenceId,
                imageSourceUrl = artist.imageSourceUrl,
                websiteUrl = artist.websiteUrl,
                facebookUrl = artist.facebookUrl,
                instagramUrl = artist.instagramUrl,
                youtubeUrl = artist.youtubeUrl,
                musicbrainzId = artist.musicbrainzId,
                musicbrainzMatch = artist.musicbrainzMatch.name,
                musicbrainzCheckedAt = artist.musicbrainzCheckedAt,
                createdAt = artist.createdAt,
                updatedAt = artist.updatedAt
            )
    }
}
