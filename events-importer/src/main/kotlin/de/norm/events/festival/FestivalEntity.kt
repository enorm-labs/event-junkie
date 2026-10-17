package de.norm.events.festival

import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant
import java.time.LocalDate

/** R2DBC entity mapped to the `festival` table. [firstDay] and [lastDay] are Berlin dates. */
@Table("festival")
data class FestivalEntity(
    @Id val id: Long? = null,
    val slug: String,
    val name: String,
    val url: String,
    val firstDay: LocalDate,
    val lastDay: LocalDate,
    val noticeDe: String,
    val noticeEn: String,
    @CreatedDate val createdAt: Instant? = null,
    @LastModifiedDate val updatedAt: Instant? = null
)
