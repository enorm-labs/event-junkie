package de.norm.events.artist

import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant

/**
 * Response DTO for an artist.
 *
 * Decouples the API contract from the domain model so that internal
 * domain changes do not automatically become breaking API changes.
 */
@Schema(description = "Response DTO for an artist")
data class ArtistResponse(
    @Schema(description = "Database primary key", example = "7")
    val id: Long,
    @Schema(description = "Stage name or band name", example = "The Adicts")
    val name: String,
    @Schema(description = "URL-friendly identifier, derived from the name", example = "the-adicts")
    val slug: String,
    @Schema(description = "Biography or description text", example = "Formed in Ipswich in the late 1970s…")
    val description: String?,
    @Schema(description = "URL of the artist's photo or band logo", example = "https://example.com/adicts.jpg")
    val imageUrl: String?,
    @Schema(description = "Who to credit for `imageUrl`", example = "Photographer Name, via Wikimedia Commons")
    val imageAttribution: String?,
    @Schema(description = "SPDX identifier of the licence `imageUrl` is published under", example = "CC-BY-SA-4.0")
    val imageLicenceId: String?,
    @Schema(description = "The image's description page, which the rendered credit links to", example = "https://commons.wikimedia.org/wiki/File:Example.jpg")
    val imageSourceUrl: String?,
    @Schema(description = "URL of the artist's official homepage", example = "https://theadicts.net/")
    val websiteUrl: String?,
    @Schema(description = "URL of the artist's Facebook page", example = "https://www.facebook.com/theadicts")
    val facebookUrl: String?,
    @Schema(description = "URL of the artist's Instagram profile", example = "https://www.instagram.com/theadictsofficial/")
    val instagramUrl: String?,
    @Schema(description = "URL of the artist's YouTube channel", example = "https://www.youtube.com/@theadictsofficial")
    val youtubeUrl: String?,
    @Schema(description = "URL of the artist's Bandcamp page", example = "https://theadicts.bandcamp.com/")
    val bandcampUrl: String?,
    @Schema(description = "URL of the artist's SoundCloud profile", example = "https://soundcloud.com/theadicts")
    val soundcloudUrl: String?,
    @Schema(description = "URL of the artist's Discogs page", example = "https://www.discogs.com/artist/252143")
    val discogsUrl: String?,
    @Schema(description = "URL of the artist's Wikidata item", example = "https://www.wikidata.org/wiki/Q1414437")
    val wikidataUrl: String?,
    @Schema(description = "URL of the artist's Resident Advisor page", example = "https://ra.co/dj/theadicts")
    val residentAdvisorUrl: String?,
    @Schema(description = "URL of the artist's Spotify page", example = "https://open.spotify.com/artist/5dqOB8KIVGgFxbELxDfJcz")
    val spotifyUrl: String?,
    @Schema(description = "What kind of act this is, as MusicBrainz types it; null until an EXACT match is read", example = "GROUP")
    val artistType: ArtistType?,
    @Schema(description = "When the act formed, MusicBrainz's partial date; ensembles only", example = "1975")
    val founded: String?,
    @Schema(description = "Where the act formed; ensembles only", example = "Ipswich")
    val foundedIn: String?,
    @Schema(description = "ISO 3166-1 code of the country the act is from", example = "GB")
    val country: String?,
    @Schema(description = "MusicBrainz artist id (MBID), set exactly when `musicbrainzMatch` is `EXACT`", example = "3ec6ee6a-88e6-4e7b-8f3f-7a4b2d5c8a1e")
    val musicbrainzId: String?,
    @Schema(description = "What the MusicBrainz lookup decided about the name (ADR-031)", example = "EXACT")
    val musicbrainzMatch: MusicBrainzMatch,
    @Schema(description = "When that verdict was reached")
    val musicbrainzCheckedAt: Instant?,
    @Schema(description = "When the enrichment last read the MusicBrainz entity (ADR-031, step C)")
    val musicbrainzEnrichedAt: Instant?,
    @Schema(description = "Timestamp when this record was first created")
    val createdAt: Instant?,
    @Schema(description = "Timestamp when this record was last modified")
    val updatedAt: Instant?
) {
    companion object {
        fun fromDomain(artist: Artist): ArtistResponse =
            ArtistResponse(
                id = requireNotNull(artist.id) { "Persisted artist must have an ID" },
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
                bandcampUrl = artist.bandcampUrl,
                soundcloudUrl = artist.soundcloudUrl,
                discogsUrl = artist.discogsUrl,
                wikidataUrl = artist.wikidataUrl,
                residentAdvisorUrl = artist.residentAdvisorUrl,
                spotifyUrl = artist.spotifyUrl,
                artistType = artist.artistType,
                founded = artist.founded,
                foundedIn = artist.foundedIn,
                country = artist.country,
                musicbrainzId = artist.musicbrainzId,
                musicbrainzMatch = artist.musicbrainzMatch,
                musicbrainzCheckedAt = artist.musicbrainzCheckedAt,
                musicbrainzEnrichedAt = artist.musicbrainzEnrichedAt,
                createdAt = artist.createdAt,
                updatedAt = artist.updatedAt
            )
    }
}
