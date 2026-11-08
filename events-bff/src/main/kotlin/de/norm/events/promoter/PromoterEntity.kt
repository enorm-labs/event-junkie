package de.norm.events.promoter

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

/**
 * Read-only R2DBC entity mapped to the `promoter` table.
 *
 * Lean projection for the BFF. The table is owned and written by the importer; the description
 * columns reach the detail response only, not the summary embedded in events.
 */
@Table("promoter")
data class PromoterEntity(
    @Id val id: Long? = null,
    val name: String,
    val slug: String,
    val websiteUrl: String? = null,
    val instagramUrl: String? = null,
    val imageUrl: String? = null,
    val imageAttribution: String? = null,
    val imageLicenceId: String? = null,
    val imageSourceUrl: String? = null,
    val description: String? = null,
    val descriptionLanguage: String? = null,
    val descriptionAlt: String? = null,
    val descriptionAltLanguage: String? = null,
    val createdAt: Instant? = null,
    val updatedAt: Instant? = null
) {
    fun requiredId(): Long = requireNotNull(id) { "Persisted promoter must have an ID" }
}
