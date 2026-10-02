package de.norm.events.venue

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.math.BigDecimal
import java.time.Instant

/**
 * Read-only R2DBC entity mapped to the `venue` table.
 *
 * The BFF only reads venues, so this is a lean projection of the columns the public
 * API exposes (no auditing/write annotations). The table is owned and written by the
 * importer; the BFF shares the same `events` schema.
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
    val imageAttribution: String? = null,
    val imageLicenceId: String? = null,
    val imageSourceUrl: String? = null,
    val description: String? = null,
    val descriptionLanguage: String? = null,
    val descriptionAlt: String? = null,
    val descriptionAltLanguage: String? = null,
    val venueTypes: List<String> = emptyList(),
    val capacity: Int? = null,
    val programmeFamilies: List<String> = emptyList(),
    val programmeEventTypes: List<String> = emptyList(),
    val createdAt: Instant? = null,
    val updatedAt: Instant? = null
)
