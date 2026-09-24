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
    val descriptionLanguage: String? = null,
    val descriptionAttribution: String? = null,
    val descriptionLicenceId: String? = null,
    val descriptionSourceUrl: String? = null,
    val descriptionAlt: String? = null,
    val descriptionAltLanguage: String? = null,
    val descriptionAltAttribution: String? = null,
    val descriptionAltLicenceId: String? = null,
    val descriptionAltSourceUrl: String? = null,
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
    /** [ArtistType] by name, a String for the same reason as [musicbrainzMatch]. */
    val artistType: String? = null,
    val founded: String? = null,
    val foundedIn: String? = null,
    val country: String? = null,
    val musicbrainzId: String? = null,
    /** [MusicBrainzMatch] by name; a String so R2DBC needs no converter, as `event.status` does it. */
    val musicbrainzMatch: String = MusicBrainzMatch.UNCHECKED.name,
    val musicbrainzCheckedAt: Instant? = null,
    val musicbrainzEnrichedAt: Instant? = null,
    @CreatedDate val createdAt: Instant? = null,
    @LastModifiedDate val updatedAt: Instant? = null
) {
    fun toDomain(): Artist =
        Artist(
            id = id,
            name = name,
            slug = slug,
            description = description,
            descriptionLanguage = descriptionLanguage,
            descriptionAttribution = descriptionAttribution,
            descriptionLicenceId = descriptionLicenceId,
            descriptionSourceUrl = descriptionSourceUrl,
            descriptionAlt = descriptionAlt,
            descriptionAltLanguage = descriptionAltLanguage,
            descriptionAltAttribution = descriptionAltAttribution,
            descriptionAltLicenceId = descriptionAltLicenceId,
            descriptionAltSourceUrl = descriptionAltSourceUrl,
            imageUrl = imageUrl,
            imageAttribution = imageAttribution,
            imageLicenceId = imageLicenceId,
            imageSourceUrl = imageSourceUrl,
            websiteUrl = websiteUrl,
            facebookUrl = facebookUrl,
            instagramUrl = instagramUrl,
            youtubeUrl = youtubeUrl,
            bandcampUrl = bandcampUrl,
            soundcloudUrl = soundcloudUrl,
            discogsUrl = discogsUrl,
            wikidataUrl = wikidataUrl,
            residentAdvisorUrl = residentAdvisorUrl,
            spotifyUrl = spotifyUrl,
            artistType = artistType?.let { ArtistType.valueOf(it) },
            founded = founded,
            foundedIn = foundedIn,
            country = country,
            musicbrainzId = musicbrainzId,
            musicbrainzMatch = MusicBrainzMatch.valueOf(musicbrainzMatch),
            musicbrainzCheckedAt = musicbrainzCheckedAt,
            musicbrainzEnrichedAt = musicbrainzEnrichedAt,
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
                descriptionLanguage = artist.descriptionLanguage,
                descriptionAttribution = artist.descriptionAttribution,
                descriptionLicenceId = artist.descriptionLicenceId,
                descriptionSourceUrl = artist.descriptionSourceUrl,
                descriptionAlt = artist.descriptionAlt,
                descriptionAltLanguage = artist.descriptionAltLanguage,
                descriptionAltAttribution = artist.descriptionAltAttribution,
                descriptionAltLicenceId = artist.descriptionAltLicenceId,
                descriptionAltSourceUrl = artist.descriptionAltSourceUrl,
                imageUrl = artist.imageUrl,
                imageAttribution = artist.imageAttribution,
                imageLicenceId = artist.imageLicenceId,
                imageSourceUrl = artist.imageSourceUrl,
                websiteUrl = artist.websiteUrl,
                facebookUrl = artist.facebookUrl,
                instagramUrl = artist.instagramUrl,
                youtubeUrl = artist.youtubeUrl,
                bandcampUrl = artist.bandcampUrl,
                soundcloudUrl = artist.soundcloudUrl,
                discogsUrl = artist.discogsUrl,
                wikidataUrl = artist.wikidataUrl,
                residentAdvisorUrl = artist.residentAdvisorUrl,
                spotifyUrl = artist.spotifyUrl,
                artistType = artist.artistType?.name,
                founded = artist.founded,
                foundedIn = artist.foundedIn,
                country = artist.country,
                musicbrainzId = artist.musicbrainzId,
                musicbrainzMatch = artist.musicbrainzMatch.name,
                musicbrainzCheckedAt = artist.musicbrainzCheckedAt,
                musicbrainzEnrichedAt = artist.musicbrainzEnrichedAt,
                createdAt = artist.createdAt,
                updatedAt = artist.updatedAt
            )
    }
}
