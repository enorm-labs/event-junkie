package de.norm.events.venue

import de.norm.events.image.IMAGE_ATTRIBUTION_DESCRIPTION
import de.norm.events.image.IMAGE_LICENCE_ID_DESCRIPTION
import de.norm.events.image.IMAGE_SOURCES_DESCRIPTION
import de.norm.events.image.IMAGE_SOURCE_URL_DESCRIPTION
import de.norm.events.image.INTRINSIC_HEIGHT_DESCRIPTION
import de.norm.events.image.INTRINSIC_WIDTH_DESCRIPTION
import de.norm.events.image.ImageSourceResponse
import de.norm.events.image.ServedImage
import io.swagger.v3.oas.annotations.media.Schema
import java.math.BigDecimal

/**
 * Compact venue representation embedded in event responses.
 */
@Schema(description = "Compact venue summary")
data class VenueSummaryResponse(
    @Schema(description = "Database primary key", example = "42")
    val id: Long,
    @Schema(description = "URL-friendly identifier", example = "astra-kulturhaus")
    val slug: String,
    @Schema(description = "Display name of the venue", example = "Astra Kulturhaus")
    val name: String,
    @Schema(description = "City where the venue is located", example = "Berlin")
    val city: String,
    @Schema(description = "Street address of the venue", example = "Revaler Str. 99")
    val address: String?,
    @Schema(description = "Berlin district (one of the 23 pre-2001 Bezirke) as a canonical slug", example = "kreuzberg")
    val district: String?,
    @Schema(description = "Geographic latitude for map display", example = "52.507242")
    val latitude: BigDecimal?,
    @Schema(description = "Geographic longitude for map display", example = "13.451803")
    val longitude: BigDecimal?,
    @Schema(description = "URL of the venue's logo or photo")
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
            entity: VenueEntity,
            image: ServedImage
        ): VenueSummaryResponse =
            VenueSummaryResponse(
                id = requireNotNull(entity.id) { "Persisted venue must have an ID" },
                slug = entity.slug,
                name = entity.name,
                city = entity.city,
                address = entity.address,
                district = entity.district,
                latitude = entity.latitude,
                longitude = entity.longitude,
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
 * One venue on the venue list: the summary fields plus its upcoming events, the count the list
 * can sort by (#360). Separate from [VenueSummaryResponse] so an event's embedded venue does not
 * carry a count it never computes.
 */
@Schema(description = "Venue on the venue list")
data class VenueListItemResponse(
    @Schema(description = "Database primary key", example = "42")
    val id: Long,
    @Schema(description = "URL-friendly identifier", example = "astra-kulturhaus")
    val slug: String,
    @Schema(description = "Display name of the venue", example = "Astra Kulturhaus")
    val name: String,
    @Schema(description = "City where the venue is located", example = "Berlin")
    val city: String,
    @Schema(description = "Street address of the venue", example = "Revaler Str. 99")
    val address: String?,
    @Schema(description = "Berlin district (one of the 23 pre-2001 Bezirke) as a canonical slug", example = "kreuzberg")
    val district: String?,
    @Schema(description = "Geographic latitude for map display", example = "52.507242")
    val latitude: BigDecimal?,
    @Schema(description = "Geographic longitude for map display", example = "13.451803")
    val longitude: BigDecimal?,
    @Schema(description = "URL of the venue's logo or photo")
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
    @Schema(description = "Events from today on at this venue", example = "12")
    val upcomingEventCount: Int,
    @Schema(description = VENUE_TYPES_DESCRIPTION, example = "[\"live-venue\", \"club\"]")
    val venueTypes: List<String>,
    @Schema(description = CAPACITY_DESCRIPTION, example = "1500")
    val capacity: Int?,
    @Schema(description = PROGRAMME_FAMILIES_DESCRIPTION, example = "[\"rock\", \"punk\"]")
    val programmeFamilies: List<String>,
    @Schema(description = PROGRAMME_EVENT_TYPES_DESCRIPTION, example = "[\"CONCERT\", \"PARTY\"]")
    val programmeEventTypes: List<String>,
    @Schema(description = CHARACTER_TAGS_DESCRIPTION, example = "[\"queer\", \"awareness-team\"]")
    val characterTags: List<String>
) {
    companion object {
        fun fromEntity(
            entity: VenueEntity,
            image: ServedImage,
            upcomingEventCount: Int,
            characterTags: List<String>
        ): VenueListItemResponse =
            VenueListItemResponse(
                id = requireNotNull(entity.id) { "Persisted venue must have an ID" },
                slug = entity.slug,
                name = entity.name,
                city = entity.city,
                address = entity.address,
                district = entity.district,
                latitude = entity.latitude,
                longitude = entity.longitude,
                imageUrl = image.url,
                imageAttribution = entity.imageAttribution,
                imageLicenceId = entity.imageLicenceId,
                imageSourceUrl = entity.imageSourceUrl,
                imageSources = image.sources,
                intrinsicWidth = image.intrinsicWidth,
                intrinsicHeight = image.intrinsicHeight,
                upcomingEventCount = upcomingEventCount,
                venueTypes = entity.venueTypes,
                capacity = entity.capacity,
                programmeFamilies = entity.programmeFamilies,
                programmeEventTypes = entity.programmeEventTypes,
                characterTags = characterTags
            )
    }
}

/**
 * Full venue representation for the venue detail page.
 *
 * Events at this venue are intentionally not embedded — the frontend fetches them via
 * `GET /events?venue=<slug>`, which keeps modules decoupled and reuses the event filter.
 */
@Schema(description = "Full venue detail")
data class VenueDetailResponse(
    @Schema(description = "Database primary key", example = "42")
    val id: Long,
    @Schema(description = "URL-friendly identifier", example = "astra-kulturhaus")
    val slug: String,
    @Schema(description = "Display name of the venue", example = "Astra Kulturhaus")
    val name: String,
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
    @Schema(description = "URL of the venue's official website")
    val websiteUrl: String?,
    @Schema(description = "URL of the venue's logo or photo")
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
    @Schema(description = "Short prose description of the venue")
    val description: String?,
    @Schema(description = "Language of `description`: `de` or `en`. Null when the language is unknown.", example = "en")
    val descriptionLanguage: String?,
    @Schema(
        description =
            "The same description in the other language. Written by hand, not machine-translated, " +
                "so it carries no origin and needs no disclosure (#1210).",
        example = "Ein Konzertsaal in Friedrichshain."
    )
    val descriptionAlt: String?,
    @Schema(description = "Language of `descriptionAlt`: `de` or `en`. Null exactly when `descriptionAlt` is.", example = "de")
    val descriptionAltLanguage: String?,
    @Schema(description = VENUE_TYPES_DESCRIPTION, example = "[\"live-venue\", \"club\"]")
    val venueTypes: List<String>,
    @Schema(description = CAPACITY_DESCRIPTION, example = "1500")
    val capacity: Int?,
    @Schema(description = PROGRAMME_FAMILIES_DESCRIPTION, example = "[\"rock\", \"punk\"]")
    val programmeFamilies: List<String>,
    @Schema(description = PROGRAMME_EVENT_TYPES_DESCRIPTION, example = "[\"CONCERT\", \"PARTY\"]")
    val programmeEventTypes: List<String>,
    @Schema(description = "$CHARACTER_TAGS_DESCRIPTION Each carries the URL of the venue's own page that states it.")
    val characterTags: List<VenueCharacterTagResponse>
) {
    companion object {
        fun fromEntity(
            entity: VenueEntity,
            image: ServedImage,
            characterTags: List<VenueCharacterTagResponse>
        ): VenueDetailResponse =
            VenueDetailResponse(
                id = requireNotNull(entity.id) { "Persisted venue must have an ID" },
                slug = entity.slug,
                name = entity.name,
                address = entity.address,
                city = entity.city,
                postalCode = entity.postalCode,
                district = entity.district,
                latitude = entity.latitude,
                longitude = entity.longitude,
                websiteUrl = entity.websiteUrl,
                imageUrl = image.url,
                imageAttribution = entity.imageAttribution,
                imageLicenceId = entity.imageLicenceId,
                imageSourceUrl = entity.imageSourceUrl,
                imageSources = image.sources,
                intrinsicWidth = image.intrinsicWidth,
                intrinsicHeight = image.intrinsicHeight,
                description = entity.description,
                descriptionLanguage = entity.descriptionLanguage,
                descriptionAlt = entity.descriptionAlt,
                descriptionAltLanguage = entity.descriptionAltLanguage,
                venueTypes = entity.venueTypes,
                capacity = entity.capacity,
                programmeFamilies = entity.programmeFamilies,
                programmeEventTypes = entity.programmeEventTypes,
                characterTags = characterTags
            )
    }
}

/** A character tag on a venue and the venue's own page that states it (#2379). */
@Schema(description = "A character tag and the venue's own page that states it")
data class VenueCharacterTagResponse(
    @Schema(description = "Character tag slug", example = "queer")
    val tag: String,
    @Schema(description = "URL of the venue's own page that states the tag", example = "https://www.so36.com/about")
    val sourceUrl: String
)

private const val VENUE_TYPES_DESCRIPTION = "What kind of place this is, as venue type slugs, curated by hand. Empty until curated."
private const val CHARACTER_TAGS_DESCRIPTION =
    "What the venue says about itself (its kind of space, its door, its access), as character tag slugs, ordered by slug. Set only where the venue's own page states it."
private const val CAPACITY_DESCRIPTION = "How many visitors the largest room holds, as the venue publishes it. Null where it does not."
private const val PROGRAMME_FAMILIES_DESCRIPTION =
    "Genre family slugs the venue mostly programmes, most frequent first, derived from its events of the last year and ahead."
private const val PROGRAMME_EVENT_TYPES_DESCRIPTION =
    "Event types the venue hosts, most frequent first, derived from its events like `programmeFamilies`."
