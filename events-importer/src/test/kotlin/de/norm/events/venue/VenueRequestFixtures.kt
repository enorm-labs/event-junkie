package de.norm.events.venue

import java.math.BigDecimal

/**
 * Test fixture factory for [VenueRequest] DTOs.
 *
 * Creates request objects with sensible defaults so tests only need to
 * specify the properties relevant to the scenario under test.
 *
 * ```kotlin
 * val request = VenueRequestFixtures.astra()
 * val request = VenueRequestFixtures.create(name = "Privatclub")
 * ```
 */
object VenueRequestFixtures {
    /** Default venue request modelled after Astra Kulturhaus. */
    fun astra(
        name: String = "Astra Kulturhaus",
        address: String? = "Revaler Str. 99",
        city: String = "Berlin",
        postalCode: String? = "10245",
        district: District? = District.FRIEDRICHSHAIN_KREUZBERG,
        latitude: BigDecimal? = null,
        longitude: BigDecimal? = null,
        websiteUrl: String? = "https://www.astra-berlin.de",
        imageUrl: String? = null,
        description: String? = "A large concert hall on the RAW-Gelände in Friedrichshain."
    ): VenueRequest =
        VenueRequest(
            name = name,
            address = address,
            city = city,
            postalCode = postalCode,
            district = district,
            latitude = latitude,
            longitude = longitude,
            websiteUrl = websiteUrl,
            imageUrl = imageUrl,
            description = description
        )

    /** Creates a [VenueRequest] with minimal defaults. */
    fun create(
        name: String = "Test Venue",
        address: String? = null,
        city: String = "Berlin",
        postalCode: String? = null,
        district: District? = null,
        latitude: BigDecimal? = null,
        longitude: BigDecimal? = null,
        websiteUrl: String? = null,
        imageUrl: String? = null,
        description: String? = null
    ): VenueRequest =
        VenueRequest(
            name = name,
            address = address,
            city = city,
            postalCode = postalCode,
            district = district,
            latitude = latitude,
            longitude = longitude,
            websiteUrl = websiteUrl,
            imageUrl = imageUrl,
            description = description
        )
}
