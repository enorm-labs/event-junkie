package de.norm.events.festival

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.LocalDate

/** Read-only R2DBC entity mapped to the `festival` table, which the importer owns and writes. */
@Table("festival")
data class FestivalEntity(
    @Id val id: Long? = null,
    val slug: String,
    val name: String,
    val url: String,
    val firstDay: LocalDate,
    val lastDay: LocalDate,
    val noticeDe: String,
    val noticeEn: String
)
