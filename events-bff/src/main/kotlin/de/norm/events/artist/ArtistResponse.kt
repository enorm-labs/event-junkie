package de.norm.events.artist

import de.norm.events.image.IMAGE_ATTRIBUTION_DESCRIPTION
import de.norm.events.image.IMAGE_LICENCE_ID_DESCRIPTION
import de.norm.events.image.IMAGE_SOURCES_DESCRIPTION
import de.norm.events.image.IMAGE_SOURCE_URL_DESCRIPTION
import de.norm.events.image.INTRINSIC_HEIGHT_DESCRIPTION
import de.norm.events.image.INTRINSIC_WIDTH_DESCRIPTION
import de.norm.events.image.ImageSourceResponse
import de.norm.events.image.ServedImage
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant

/**
 * Compact artist representation embedded in event lineups and returned by the artist list.
 */
@Schema(description = "Compact artist summary")
data class ArtistSummaryResponse(
    @Schema(description = "Database primary key", example = "7")
    val id: Long,
    @Schema(description = "URL-friendly identifier", example = "the-adicts")
    val slug: String,
    @Schema(description = "Stage name or band name", example = "The Adicts")
    val name: String,
    @Schema(description = "URL of the artist's photo or band logo")
    val imageUrl: String?,
    @Schema(description = IMAGE_ATTRIBUTION_DESCRIPTION, example = "Photographer Name, via Wikimedia Commons")
    val imageAttribution: String?,
    @Schema(description = IMAGE_LICENCE_ID_DESCRIPTION, example = "CC-BY-SA-4.0")
    val imageLicenceId: String?,
    @Schema(description = IMAGE_SOURCE_URL_DESCRIPTION, example = "https://commons.wikimedia.org/wiki/File:Example.jpg")
    val imageSourceUrl: String?,
    @Schema(description = IMAGE_SOURCES_DESCRIPTION)
    val imageSources: List<ImageSourceResponse>,
    @Schema(description = INTRINSIC_WIDTH_DESCRIPTION, example = "1200")
    val intrinsicWidth: Int?,
    @Schema(description = INTRINSIC_HEIGHT_DESCRIPTION, example = "630")
    val intrinsicHeight: Int?
) {
    companion object {
        fun fromEntity(
            entity: ArtistEntity,
            image: ServedImage
        ): ArtistSummaryResponse =
            ArtistSummaryResponse(
                id = requireNotNull(entity.id) { "Persisted artist must have an ID" },
                slug = entity.slug,
                name = entity.name,
                imageUrl = image.url,
                imageAttribution = entity.imageAttribution,
                imageLicenceId = entity.imageLicenceId,
                imageSourceUrl = entity.imageSourceUrl,
                imageSources = image.sources,
                intrinsicWidth = image.intrinsicWidth,
                intrinsicHeight = image.intrinsicHeight
            )
    }
}

/**
 * Full artist representation for the artist detail page.
 *
 * Events featuring this artist are not embedded — the frontend fetches them via
 * `GET /events?artist=<slug>`.
 */
@Schema(description = "Full artist detail")
data class ArtistDetailResponse(
    @Schema(description = "Database primary key", example = "7")
    val id: Long,
    @Schema(description = "URL-friendly identifier", example = "the-adicts")
    val slug: String,
    @Schema(description = "Stage name or band name", example = "The Adicts")
    val name: String,
    @Schema(description = "Biography or description text")
    val description: String?,
    @Schema(description = "Language of `description`: `de` or `en`. Null when the language is unknown.", example = "en")
    val descriptionLanguage: String?,
    @Schema(
        description =
            "Who to credit for `description` when it is licensed text. Null for a text a venue or a person wrote; " +
                "set exactly when `descriptionLicenceId` and `descriptionSourceUrl` are",
        example = "Wikipedia"
    )
    val descriptionAttribution: String?,
    @Schema(description = "SPDX identifier of the licence `description` is published under", example = "CC-BY-SA-4.0")
    val descriptionLicenceId: String?,
    @Schema(description = "The page `description` was taken from, which the rendered credit links to", example = "https://en.wikipedia.org/wiki/Buffalo_Tom")
    val descriptionSourceUrl: String?,
    @Schema(description = "URL of the artist's photo or band logo")
    val imageUrl: String?,
    @Schema(description = IMAGE_ATTRIBUTION_DESCRIPTION, example = "Photographer Name, via Wikimedia Commons")
    val imageAttribution: String?,
    @Schema(description = IMAGE_LICENCE_ID_DESCRIPTION, example = "CC-BY-SA-4.0")
    val imageLicenceId: String?,
    @Schema(description = IMAGE_SOURCE_URL_DESCRIPTION, example = "https://commons.wikimedia.org/wiki/File:Example.jpg")
    val imageSourceUrl: String?,
    @Schema(description = IMAGE_SOURCES_DESCRIPTION)
    val imageSources: List<ImageSourceResponse>,
    @Schema(description = INTRINSIC_WIDTH_DESCRIPTION, example = "1200")
    val intrinsicWidth: Int?,
    @Schema(description = INTRINSIC_HEIGHT_DESCRIPTION, example = "630")
    val intrinsicHeight: Int?,
    @Schema(description = "URL of the artist's official homepage")
    val websiteUrl: String?,
    @Schema(description = "URL of the artist's Facebook page")
    val facebookUrl: String?,
    @Schema(description = "URL of the artist's Instagram profile")
    val instagramUrl: String?,
    @Schema(description = "URL of the artist's YouTube channel")
    val youtubeUrl: String?,
    @Schema(description = "URL of the artist's Bandcamp page")
    val bandcampUrl: String?,
    @Schema(description = "URL of the artist's SoundCloud profile")
    val soundcloudUrl: String?,
    @Schema(description = "URL of the artist's Discogs page")
    val discogsUrl: String?,
    @Schema(description = "URL of the artist's Resident Advisor page")
    val residentAdvisorUrl: String?,
    @Schema(description = "URL of the artist's Spotify page")
    val spotifyUrl: String?,
    @Schema(
        description = "The artist's MusicBrainz page, the correction path for a wrong match; set exactly when `musicbrainzMatch` is `EXACT`",
        example = "https://musicbrainz.org/artist/41f4d85a-0bd7-4602-a3e3-8c47f36efb0a"
    )
    val musicbrainzUrl: String?,
    @Schema(description = "What kind of act this is, as MusicBrainz types it; null until read", example = "GROUP")
    val artistType: ArtistType?,
    @Schema(description = "MusicBrainz artist id (MBID), set exactly when `musicbrainzMatch` is `EXACT`", example = "41f4d85a-0bd7-4602-a3e3-8c47f36efb0a")
    val musicbrainzId: String?,
    @Schema(description = "What the MusicBrainz lookup decided about the name (ADR-031)", example = "EXACT")
    val musicbrainzMatch: MusicBrainzMatch,
    @Schema(description = "When that verdict was reached")
    val musicbrainzCheckedAt: Instant?
) {
    companion object {
        private const val MUSICBRAINZ_ARTIST_PAGE = "https://musicbrainz.org/artist/"

        fun fromEntity(
            entity: ArtistEntity,
            image: ServedImage
        ): ArtistDetailResponse =
            ArtistDetailResponse(
                id = requireNotNull(entity.id) { "Persisted artist must have an ID" },
                slug = entity.slug,
                name = entity.name,
                description = entity.description,
                descriptionLanguage = entity.descriptionLanguage,
                descriptionAttribution = entity.descriptionAttribution,
                descriptionLicenceId = entity.descriptionLicenceId,
                descriptionSourceUrl = entity.descriptionSourceUrl,
                imageUrl = image.url,
                imageAttribution = entity.imageAttribution,
                imageLicenceId = entity.imageLicenceId,
                imageSourceUrl = entity.imageSourceUrl,
                imageSources = image.sources,
                intrinsicWidth = image.intrinsicWidth,
                intrinsicHeight = image.intrinsicHeight,
                websiteUrl = entity.websiteUrl,
                facebookUrl = entity.facebookUrl,
                instagramUrl = entity.instagramUrl,
                youtubeUrl = entity.youtubeUrl,
                bandcampUrl = entity.bandcampUrl,
                soundcloudUrl = entity.soundcloudUrl,
                discogsUrl = entity.discogsUrl,
                residentAdvisorUrl = entity.residentAdvisorUrl,
                spotifyUrl = entity.spotifyUrl,
                musicbrainzUrl = entity.musicbrainzId?.let { "$MUSICBRAINZ_ARTIST_PAGE$it" },
                artistType = entity.artistType?.let { ArtistType.valueOf(it) },
                musicbrainzId = entity.musicbrainzId,
                musicbrainzMatch = MusicBrainzMatch.valueOf(entity.musicbrainzMatch),
                musicbrainzCheckedAt = entity.musicbrainzCheckedAt
            )
    }
}
