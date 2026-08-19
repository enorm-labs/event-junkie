package de.norm.events.venue

import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.relational.core.mapping.Table
import java.math.BigDecimal
import java.time.Instant

/**
 * R2DBC entity mapped to the `venue` table.
 *
 * Kept separate from the core [Venue] domain class so that `events-core` remains
 * free of Spring Data annotations. Conversion functions [toDomain] and [fromDomain]
 * bridge the two representations.
 */
@Table("venue")
data class VenueEntity(
    @Id val id: Long? = null,
    val name: String,
    val slug: String,
    val address: String? = null,
    val city: String = "Berlin",
    val postalCode: String? = null,
    val district: String? = null,
    val latitude: BigDecimal? = null,
    val longitude: BigDecimal? = null,
    val websiteUrl: String? = null,
    val imageUrl: String? = null,
    val description: String? = null,
    @CreatedDate val createdAt: Instant? = null,
    @LastModifiedDate val updatedAt: Instant? = null
) {
    fun toDomain(): Venue =
        Venue(
            id = id,
            name = name,
            slug = slug,
            address = address,
            city = city,
            postalCode = postalCode,
            district = district,
            latitude = latitude,
            longitude = longitude,
            websiteUrl = websiteUrl,
            imageUrl = imageUrl,
            description = description,
            createdAt = createdAt,
            updatedAt = updatedAt
        )

    companion object {
        fun fromDomain(venue: Venue): VenueEntity =
            VenueEntity(
                id = venue.id,
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
                description = venue.description,
                createdAt = venue.createdAt,
                updatedAt = venue.updatedAt
            )
    }
}
