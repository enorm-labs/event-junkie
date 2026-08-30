package de.norm.events.venue

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.math.BigDecimal

/**
 * Request body for creating or updating a venue.
 *
 * Only includes user-provided fields — `id`, `createdAt`, and `updatedAt`
 * are managed by the database. For updates, the `id` is taken from the path parameter.
 * Slugs are a server-side concern computed by the service layer, so they are not
 * part of the request DTO.
 */
@Schema(description = "Request body for creating or updating a venue")
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
    // borough filter (#329). The entity keeps a String, the same split SourceLicence uses.
    @Schema(
        description = "Berlin borough (Bezirk) as a canonical slug. One of the twelve, never an Ortsteil",
        example = "friedrichshain-kreuzberg"
    )
    val district: District? = null,
    @Schema(description = "Geographic latitude for map display", example = "52.507242")
    val latitude: BigDecimal? = null,
    @Schema(description = "Geographic longitude for map display", example = "13.451803")
    val longitude: BigDecimal? = null,
    @field:Size(max = 2048, message = "Website URL must not exceed 2048 characters")
    @Schema(description = "URL of the venue's official website", example = "https://www.astra-berlin.de")
    val websiteUrl: String? = null,
    @field:Size(max = 2048, message = "Image URL must not exceed 2048 characters")
    @Schema(description = "URL of the venue's logo or photo", example = "https://example.com/astra-logo.jpg")
    val imageUrl: String? = null,
    @field:Size(max = 4000, message = "Description must not exceed 4000 characters")
    @Schema(
        description = "Short prose description of the venue, shown on the detail page",
        example = "A former power plant turned techno institution in Friedrichshain."
    )
    val description: String? = null
)
