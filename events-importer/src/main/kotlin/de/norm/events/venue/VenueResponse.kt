package de.norm.events.venue

import io.swagger.v3.oas.annotations.media.Schema
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/**
 * Response DTO for a venue.
 *
 * Decouples the API contract from the domain model so that internal
 * domain changes do not automatically become breaking API changes.
 */
@Schema(description = "Response DTO for a venue")
data class VenueResponse(
    @Schema(description = "Database primary key", example = "42")
    val id: Long,
    @Schema(description = "Display name of the venue", example = "Astra Kulturhaus")
    val name: String,
    @Schema(description = "URL-friendly identifier, derived from the name", example = "astra-kulturhaus")
    val slug: String,
    @Schema(description = "Street address of the venue", example = "Revaler Str. 99")
    val address: String?,
    @Schema(description = "City where the venue is located", example = "Berlin")
    val city: String,
    @Schema(description = "Postal code of the venue's address", example = "10245")
    val postalCode: String?,
    @Schema(description = "Berlin district (one of the 23 pre-2001 Bezirke) as a canonical slug", example = "kreuzberg")
    val district: String?,
    @Schema(description = "Geographic latitude for map display", example = "52.507242")
    val latitude: BigDecimal?,
    @Schema(description = "Geographic longitude for map display", example = "13.451803")
    val longitude: BigDecimal?,
    @Schema(description = "URL of the venue's official website", example = "https://www.astra-berlin.de")
    val websiteUrl: String?,
    @Schema(description = "URL of the venue's logo or photo", example = "https://example.com/astra-logo.jpg")
    val imageUrl: String?,
    @Schema(description = "Who to credit for `imageUrl`", example = "Photographer Name, via Wikimedia Commons")
    val imageAttribution: String?,
    @Schema(description = "SPDX identifier of the licence `imageUrl` is published under", example = "CC-BY-SA-4.0")
    val imageLicenceId: String?,
    @Schema(description = "The image's description page, which the rendered credit links to", example = "https://commons.wikimedia.org/wiki/File:Example.jpg")
    val imageSourceUrl: String?,
    @Schema(
        description = "Short prose description of the venue, shown on the detail page",
        example = "A former power plant turned techno institution in Friedrichshain."
    )
    val description: String?,
    @Schema(description = "Language of `description`: `de` or `en`", example = "en")
    val descriptionLanguage: String?,
    @Schema(description = "The same description in the other language, hand-written", example = "Ein Konzertsaal in Friedrichshain.")
    val descriptionAlt: String?,
    @Schema(description = "Language of `descriptionAlt`: `de` or `en`. Null exactly when `descriptionAlt` is.", example = "de")
    val descriptionAltLanguage: String?,
    @Schema(description = "What kind of place this is, as `VenueType` slugs, curated by hand", example = "[\"live-venue\", \"club\"]")
    val venueTypes: List<String>,
    @Schema(description = "How many visitors the largest room holds, where the venue publishes it", example = "1500")
    val capacity: Int?,
    @Schema(description = "Genre family slugs the venue mostly programmes, derived from its events; read-only", example = "[\"rock\", \"punk\"]")
    val programmeFamilies: List<String>,
    @Schema(description = "Event type names the venue hosts, derived from its events; read-only", example = "[\"CONCERT\", \"PARTY\"]")
    val programmeEventTypes: List<String>,
    @Schema(description = "Where a visitor finds the programme of a venue we do not import; never fetched", example = "https://www.example-club.de/programm")
    val programmeUrl: String?,
    @Schema(description = "When a person last confirmed the address, coordinates and opening; null when nobody has")
    val reviewedAt: Instant?,
    @Schema(description = "The last day the venue was open, when it closed for good; null while it is open", example = "2026-10-31")
    val closedOn: LocalDate?,
    @Schema(description = "Timestamp when this record was first created")
    val createdAt: Instant?,
    @Schema(description = "Timestamp when this record was last modified")
    val updatedAt: Instant?
) {
    companion object {
        fun fromDomain(venue: Venue): VenueResponse =
            VenueResponse(
                id = requireNotNull(venue.id) { "Persisted venue must have an ID" },
                name = venue.name,
                slug = venue.slug,
                address = venue.address,
                city = venue.city,
                postalCode = venue.postalCode,
                district = venue.district,
                latitude = venue.latitude,
                longitude = venue.longitude,
                websiteUrl = venue.websiteUrl,
                imageUrl = venue.imageUrl,
                imageAttribution = venue.imageAttribution,
                imageLicenceId = venue.imageLicenceId,
                imageSourceUrl = venue.imageSourceUrl,
                description = venue.description,
                descriptionLanguage = venue.descriptionLanguage,
                descriptionAlt = venue.descriptionAlt,
                descriptionAltLanguage = venue.descriptionAltLanguage,
                venueTypes = venue.venueTypes,
                capacity = venue.capacity,
                programmeFamilies = venue.programmeFamilies,
                programmeEventTypes = venue.programmeEventTypes,
                programmeUrl = venue.programmeUrl,
                reviewedAt = venue.reviewedAt,
                closedOn = venue.closedOn,
                createdAt = venue.createdAt,
                updatedAt = venue.updatedAt
            )
    }
}
