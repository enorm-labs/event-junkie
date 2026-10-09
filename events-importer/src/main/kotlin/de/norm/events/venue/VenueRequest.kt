package de.norm.events.venue

import de.norm.events.common.AttributableImage
import de.norm.events.common.AttributedImage
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/**
 * Request body for creating or updating a venue.
 *
 * Only includes user-provided fields — `id`, `createdAt`, and `updatedAt`
 * are managed by the database. For updates, the `id` is taken from the path parameter.
 * Slugs are a server-side concern computed by the service layer, so they are not
 * part of the request DTO.
 */
@Schema(description = "Request body for creating or updating a venue")
@AttributedImage
data class VenueRequest(
    @field:NotBlank(message = "Venue name must not be blank")
    @field:Size(max = 255, message = "Venue name must not exceed 255 characters")
    @Schema(description = "Display name of the venue", example = "Astra Kulturhaus", requiredMode = Schema.RequiredMode.REQUIRED)
    val name: String,
    @field:Size(max = 500, message = "Address must not exceed 500 characters")
    @Schema(description = "Street address of the venue", example = "Revaler Str. 99")
    val address: String? = null,
    @field:Size(max = 255, message = "City must not exceed 255 characters")
    @Schema(description = "City where the venue is located", example = "Berlin")
    val city: String = "Berlin",
    @field:Size(max = 20, message = "Postal code must not exceed 20 characters")
    @Schema(description = "Postal code of the venue's address", example = "10245")
    val postalCode: String? = null,
    // Typed, so an Ortsteil or a typo is a 400 rather than a venue that quietly stops matching its own
    // district filter (#329). The entity keeps a String, the same split SourceLicence uses.
    @Schema(
        description = "Berlin district as a canonical slug. One of the 23 pre-2001 districts, never a borough or an Ortsteil",
        example = "kreuzberg"
    )
    val district: District? = null,
    // Bounded to Berlin and a little beyond it (#329). **This would have caught none of the 32 wrong
    // coordinates the geo audit found** -- every one of them was already inside the city, wrong by
    // 207 m to 1810 m. It catches a different class, and a cheaper one to make: a decimal point in
    // the wrong place, a latitude and longitude swapped (13.4 is not a Berlin latitude), or a zero
    // pair from a field nobody filled in.
    //
    // Only external comparison finds a plausible-but-wrong coordinate, which is why the audit had to
    // be done and why #357 re-checks it when the map makes an error visible.
    @field:DecimalMin(value = "52.30", message = "Latitude is outside Berlin")
    @field:DecimalMax(value = "52.70", message = "Latitude is outside Berlin")
    @Schema(description = "Geographic latitude for map display", example = "52.507242")
    val latitude: BigDecimal? = null,
    @field:DecimalMin(value = "13.05", message = "Longitude is outside Berlin")
    @field:DecimalMax(value = "13.80", message = "Longitude is outside Berlin")
    @Schema(description = "Geographic longitude for map display", example = "13.451803")
    val longitude: BigDecimal? = null,
    @field:Size(max = 2048, message = "Website URL must not exceed 2048 characters")
    @Schema(description = "URL of the venue's official website", example = "https://www.astra-berlin.de")
    val websiteUrl: String? = null,
    // Pinned to the host, so a Facebook URL in the Instagram field is a 400 rather than a link labelled wrong.
    // Copied from the venue's own site or a search snippet: Meta's terms forbid fetching either host (#356).
    @field:Size(max = 2048, message = "Instagram URL must not exceed 2048 characters")
    @field:Pattern(regexp = INSTAGRAM_URL, message = "Instagram URL must be an https://www.instagram.com/ address")
    @Schema(
        description = "URL of the venue's Instagram profile, shown as a plain link beside the website. Entered by a person, never fetched.",
        example = "https://www.instagram.com/astra_kulturhaus/"
    )
    val instagramUrl: String? = null,
    @field:Size(max = 2048, message = "Facebook URL must not exceed 2048 characters")
    @field:Pattern(regexp = FACEBOOK_URL, message = "Facebook URL must be an https://www.facebook.com/ address")
    @Schema(
        description = "URL of the venue's Facebook page, shown as a plain link beside the website. Entered by a person, never fetched.",
        example = "https://www.facebook.com/astrakulturhaus/"
    )
    val facebookUrl: String? = null,
    @field:Size(max = 2048, message = "Image URL must not exceed 2048 characters")
    @Schema(description = "URL of the venue's logo or photo", example = "https://example.com/astra-logo.jpg")
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
    @field:Size(max = 4000, message = "Description must not exceed 4000 characters")
    @Schema(
        description = "Short prose description of the venue, shown on the detail page",
        example = "A former power plant turned techno institution in Friedrichshain."
    )
    val description: String? = null,
    @field:Pattern(regexp = "de|en", message = "Description language must be 'de' or 'en'")
    @Schema(description = "Language of `description`: `de` or `en`", example = "en")
    val descriptionLanguage: String? = null,
    @field:Size(max = 4000, message = "Alternate description must not exceed 4000 characters")
    @Schema(
        description = "The same description in the other language. Hand-written, not machine-translated (ADR-027 covers event text, not this).",
        example = "Ein früheres Heizkraftwerk, heute eine Techno-Institution in Friedrichshain."
    )
    val descriptionAlt: String? = null,
    @field:Pattern(regexp = "de|en", message = "Alternate description language must be 'de' or 'en'")
    @Schema(description = "Language of `descriptionAlt`: `de` or `en`", example = "de")
    val descriptionAltLanguage: String? = null,
    // Typed like district, so an unknown type is a 400 rather than a venue no filter finds.
    @Schema(description = "What kind of place this is; one or more", example = "[\"live-venue\", \"club\"]")
    val venueTypes: List<VenueType> = emptyList(),
    @field:Positive(message = "Capacity must be positive")
    @Schema(description = "How many visitors the largest room holds, as the venue publishes it", example = "1500")
    val capacity: Int? = null,
    @field:Size(max = 2048, message = "Programme URL must not exceed 2048 characters")
    @field:Pattern(regexp = "https?://.+", message = "Programme URL must start with http:// or https://")
    @Schema(
        description =
            "Where a visitor finds the programme of a venue we do not import: its own programme page, else its Resident Advisor page, " +
                "else a ticket platform. Shown as a link, never fetched. Leave it out for an imported venue (#2766).",
        example = "https://www.example-club.de/programm"
    )
    val programmeUrl: String? = null,
    @Schema(
        description = "When a person last confirmed the address, coordinates and opening. Null for a row nobody checked. Never set by an import.",
        example = "2026-10-06T18:00:00Z"
    )
    val reviewedAt: Instant? = null,
    @Schema(
        description =
            "The last day the venue was open, when it closed for good. From the day after, the public lists and the map leave it out " +
                "and its page says it closed (ADR-046). Null while it is open. Never set by an import.",
        example = "2026-10-31"
    )
    val closedOn: LocalDate? = null
) : AttributableImage

/** `https://instagram.com/…` or `https://www.instagram.com/…`, with a path. */
internal const val INSTAGRAM_URL = "https://(www\\.)?instagram\\.com/.+"

/** `https://facebook.com/…`, or a subdomain of it such as `www.` or `m.`, with a path. */
internal const val FACEBOOK_URL = "https://([a-z0-9-]+\\.)?facebook\\.com/.+"
