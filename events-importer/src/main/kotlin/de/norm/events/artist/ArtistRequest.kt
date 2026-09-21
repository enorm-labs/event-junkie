package de.norm.events.artist

import de.norm.events.common.AttributableImage
import de.norm.events.common.AttributedImage
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

/**
 * Request body for creating or updating an artist.
 *
 * Only includes user-provided fields — `id`, `createdAt`, and `updatedAt`
 * are managed by the database. For updates, the `id` is taken from the path parameter.
 * Slugs are a server-side concern computed by the service layer, so they are not
 * part of the request DTO.
 */
@Schema(description = "Request body for creating or updating an artist")
@AttributedImage
data class ArtistRequest(
    @field:NotBlank(message = "Artist name must not be blank")
    @field:Size(max = 255, message = "Artist name must not exceed 255 characters")
    @Schema(description = "Stage name or band name", example = "The Adicts", requiredMode = Schema.RequiredMode.REQUIRED)
    val name: String,
    @field:Size(max = 10000, message = "Description must not exceed 10000 characters")
    @Schema(description = "Biography or description text", example = "Formed in Ipswich in the late 1970s…")
    val description: String? = null,
    @field:Size(max = 2048, message = "Image URL must not exceed 2048 characters")
    @Schema(description = "URL of the artist's photo or band logo", example = "https://example.com/adicts.jpg")
    override val imageUrl: String? = null,
    @field:Size(max = 500, message = "Image attribution must not exceed 500 characters")
    @Schema(description = "Who to credit for `imageUrl`, worded as the archive publishes it", example = "Photographer Name, via Wikimedia Commons")
    override val imageAttribution: String? = null,
    @field:Size(max = 40, message = "Image licence identifier must not exceed 40 characters")
    @Schema(description = "SPDX identifier of the licence `imageUrl` is published under", example = "CC-BY-SA-4.0")
    override val imageLicenceId: String? = null,
    @field:Size(max = 2048, message = "Image source URL must not exceed 2048 characters")
    @Schema(
        description = "The image's description page, which the rendered credit links to",
        example = "https://commons.wikimedia.org/wiki/File:Example.jpg"
    )
    override val imageSourceUrl: String? = null,
    @field:Size(max = 2048, message = "Website URL must not exceed 2048 characters")
    @Schema(description = "URL of the artist's official homepage", example = "https://theadicts.net/")
    val websiteUrl: String? = null,
    @field:Size(max = 2048, message = "Facebook URL must not exceed 2048 characters")
    @Schema(description = "URL of the artist's Facebook page", example = "https://www.facebook.com/theadicts")
    val facebookUrl: String? = null,
    @field:Size(max = 2048, message = "Instagram URL must not exceed 2048 characters")
    @Schema(description = "URL of the artist's Instagram profile", example = "https://www.instagram.com/theadictsofficial/")
    val instagramUrl: String? = null,
    @field:Size(max = 2048, message = "YouTube URL must not exceed 2048 characters")
    @Schema(description = "URL of the artist's YouTube channel", example = "https://www.youtube.com/@theadictsofficial")
    val youtubeUrl: String? = null,
    @field:Size(max = 2048, message = "Bandcamp URL must not exceed 2048 characters")
    @Schema(description = "URL of the artist's Bandcamp page", example = "https://theadicts.bandcamp.com/")
    val bandcampUrl: String? = null,
    @field:Size(max = 2048, message = "SoundCloud URL must not exceed 2048 characters")
    @Schema(description = "URL of the artist's SoundCloud profile", example = "https://soundcloud.com/theadicts")
    val soundcloudUrl: String? = null,
    @field:Size(max = 2048, message = "Discogs URL must not exceed 2048 characters")
    @Schema(description = "URL of the artist's Discogs page", example = "https://www.discogs.com/artist/252143")
    val discogsUrl: String? = null,
    @field:Size(max = 2048, message = "Wikidata URL must not exceed 2048 characters")
    @Schema(description = "URL of the artist's Wikidata item", example = "https://www.wikidata.org/wiki/Q1414437")
    val wikidataUrl: String? = null,
    @field:Size(max = 2048, message = "Resident Advisor URL must not exceed 2048 characters")
    @Schema(description = "URL of the artist's Resident Advisor page", example = "https://ra.co/dj/theadicts")
    val residentAdvisorUrl: String? = null,
    @field:Size(max = 2048, message = "Spotify URL must not exceed 2048 characters")
    @Schema(description = "URL of the artist's Spotify page", example = "https://open.spotify.com/artist/5dqOB8KIVGgFxbELxDfJcz")
    val spotifyUrl: String? = null
) : AttributableImage
