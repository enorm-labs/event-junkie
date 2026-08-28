package de.norm.events.sourcelicence

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table

/**
 * Read-only R2DBC entity over the three `event_source` columns this module needs.
 *
 * A lean projection like [de.norm.events.event.EventEntity] — the table has 26 columns and is owned
 * and written by the importer. The two statuses are read as text rather than as the enum so an
 * unrecognised value reaches [de.norm.events.licence.SourceLicence.parseOrProhibited] instead of
 * failing the row mapping, which would take out the whole page rather than one field.
 */
@Table("event_source")
data class SourceLicenceEntity(
    @Id val id: Long? = null,
    val descriptionLicence: String? = null,
    val imageLicence: String? = null
)
