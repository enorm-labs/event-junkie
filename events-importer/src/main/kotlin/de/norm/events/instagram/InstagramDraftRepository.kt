package de.norm.events.instagram

import de.norm.events.EVENTS_SCHEMA
import io.r2dbc.spi.Readable
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.flow
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime

/** Where a draft stands. Only a person moves it out of [PENDING] (ADR-048 rule 5). */
enum class DraftStatus { PENDING, APPROVED, REJECTED, NOT_AN_EVENT }

/** One row of `instagram_draft` with its venue, as the review screen shows it. */
data class DraftRow(
    val id: Long,
    val venueId: Long,
    val venueSlug: String,
    val venueName: String,
    val mediaId: String,
    val permalink: String,
    val postedAt: OffsetDateTime,
    val caption: String?,
    val eventDate: LocalDate?,
    val startTime: LocalTime?,
    val title: String?,
    val acts: List<String>,
    val pricePresale: BigDecimal?,
    val priceBoxOffice: BigDecimal?,
    val evidence: String?,
    val confidence: Double?,
    val status: DraftStatus,
    val eventId: Long?,
    val createdAt: OffsetDateTime
)

/** One row to insert into `instagram_draft`. A [DraftStatus.NOT_AN_EVENT] row carries no event and no caption. */
data class NewDraft(
    val venueId: Long,
    val post: InstagramPost,
    val status: DraftStatus,
    val event: DraftedEvent? = null,
    val model: String? = null
)

/** The `instagram_draft` table, in hand-written SQL like `EventSuggestionRepository`: a draft row joins `venue`. */
@Repository
class InstagramDraftRepository(
    private val template: R2dbcEntityTemplate
) {
    /** The ids among [mediaIds] that have a row already: a post is sent to the model once. */
    suspend fun knownMediaIds(mediaIds: Collection<String>): Set<String> {
        if (mediaIds.isEmpty()) return emptySet()
        return template.databaseClient
            .sql("SELECT DISTINCT media_id FROM $EVENTS_SCHEMA.instagram_draft WHERE media_id = ANY(:ids)")
            .bind("ids", mediaIds.toTypedArray())
            .map { row, _ -> row.get("media_id", String::class.java).orEmpty() }
            .flow()
            .toList()
            .toSet()
    }

    /** Inserts the rows of one post. A second insert of the same post and date changes nothing. */
    suspend fun insert(drafts: List<NewDraft>): Long =
        drafts.sumOf { draft ->
            template.databaseClient
                .sql(
                    """
                    INSERT INTO $EVENTS_SCHEMA.instagram_draft
                        (venue_id, media_id, permalink, posted_at, caption, event_date, start_time, title, acts,
                         price_presale, price_box_office, evidence, confidence, model, status)
                    VALUES (:venueId, :mediaId, :permalink, :postedAt, :caption, :eventDate, :startTime, :title, :acts,
                            :pricePresale, :priceBoxOffice, :evidence, :confidence, :model, :status)
                    ON CONFLICT ON CONSTRAINT instagram_draft_one_per_post_and_date DO NOTHING
                    """.trimIndent()
                ).bind("venueId", draft.venueId)
                .bind("mediaId", draft.post.mediaId)
                .bind("permalink", draft.post.permalink)
                .bind("postedAt", draft.post.postedAt)
                .bindOrNull("caption", draft.post.caption.takeIf { draft.status == DraftStatus.PENDING }, String::class.java)
                .bindOrNull("eventDate", draft.event?.date, LocalDate::class.java)
                .bindOrNull("startTime", draft.event?.startTime, LocalTime::class.java)
                .bindOrNull("title", draft.event?.title, String::class.java)
                .bind(
                    "acts",
                    draft.event
                        ?.acts
                        .orEmpty()
                        .toTypedArray()
                ).bindOrNull("pricePresale", draft.event?.pricePresale, BigDecimal::class.java)
                .bindOrNull("priceBoxOffice", draft.event?.priceBoxOffice, BigDecimal::class.java)
                .bindOrNull("evidence", draft.event?.evidence, String::class.java)
                .bindOrNull("confidence", draft.event?.confidence, Double::class.javaObjectType)
                .bindOrNull("model", draft.model, String::class.java)
                .bind("status", draft.status.name)
                .fetch()
                .rowsUpdated()
                .awaitSingle()
        }

    /**
     * One page of drafts in [status], the soonest night first. Drafts of one venue and night sit together,
     * newest post first, so the review screen can show them as one night.
     */
    suspend fun findPage(
        status: DraftStatus,
        limit: Int,
        offset: Long
    ): List<DraftRow> =
        template.databaseClient
            .sql(
                "SELECT $COLUMNS FROM $FROM WHERE d.status = :status " +
                    "ORDER BY d.event_date NULLS LAST, v.slug, d.posted_at DESC, d.id LIMIT :limit OFFSET :offset"
            ).bind("status", status.name)
            .bind("limit", limit)
            .bind("offset", offset)
            .map { row, _ -> row.toDraft() }
            .flow()
            .toList()

    suspend fun count(status: DraftStatus): Long =
        template.databaseClient
            .sql("SELECT count(*) AS total FROM $EVENTS_SCHEMA.instagram_draft WHERE status = :status")
            .bind("status", status.name)
            .map { row, _ -> (row.get("total", Number::class.java) ?: 0).toLong() }
            .one()
            .awaitSingle()

    suspend fun findById(id: Long): DraftRow? =
        template.databaseClient
            .sql("SELECT $COLUMNS FROM $FROM WHERE d.id = :id")
            .bind("id", id)
            .map { row, _ -> row.toDraft() }
            .one()
            .awaitSingleOrNull()

    /**
     * Judges the night of draft [id]: every pending draft of its venue and date takes [status] and
     * [eventId], and loses its caption (ADR-048 rule 6). Returns how many rows changed, 0 when the
     * draft was judged already.
     */
    suspend fun judgeNight(
        id: Long,
        status: DraftStatus,
        eventId: Long?
    ): Long =
        template.databaseClient
            .sql(
                "UPDATE $EVENTS_SCHEMA.instagram_draft d SET status = :status, event_id = :eventId, caption = NULL, reviewed_at = now() " +
                    "FROM $EVENTS_SCHEMA.instagram_draft t WHERE t.id = :id AND t.status = 'PENDING' " +
                    "AND d.venue_id = t.venue_id AND d.event_date = t.event_date AND d.status = 'PENDING'"
            ).bind("status", status.name)
            .bindOrNull("eventId", eventId, Long::class.javaObjectType)
            .bind("id", id)
            .fetch()
            .rowsUpdated()
            .awaitSingle()

    /** Deletes the drafts nobody judged before [before], caption included (ADR-048 rule 6). */
    suspend fun deletePendingCreatedBefore(before: OffsetDateTime): Long =
        template.databaseClient
            .sql("DELETE FROM $EVENTS_SCHEMA.instagram_draft WHERE status = 'PENDING' AND created_at < :before")
            .bind("before", before)
            .fetch()
            .rowsUpdated()
            .awaitSingle()

    private fun <T : Any> DatabaseClient.GenericExecuteSpec.bindOrNull(
        name: String,
        value: T?,
        type: Class<T>
    ): DatabaseClient.GenericExecuteSpec = if (value == null) bindNull(name, type) else bind(name, value)

    private fun Readable.toDraft(): DraftRow =
        DraftRow(
            id = required("id", Number::class.java).toLong(),
            venueId = required("venue_id", Number::class.java).toLong(),
            venueSlug = required("venue_slug", String::class.java),
            venueName = required("venue_name", String::class.java),
            mediaId = required("media_id", String::class.java),
            permalink = required("permalink", String::class.java),
            postedAt = required("posted_at", OffsetDateTime::class.java),
            caption = get("caption", String::class.java),
            eventDate = get("event_date", LocalDate::class.java),
            startTime = get("start_time", LocalTime::class.java),
            title = get("title", String::class.java),
            acts = get("acts", Array<String>::class.java)?.toList().orEmpty(),
            pricePresale = get("price_presale", BigDecimal::class.java),
            priceBoxOffice = get("price_box_office", BigDecimal::class.java),
            evidence = get("evidence", String::class.java),
            confidence = get("confidence", Number::class.java)?.toDouble(),
            status = DraftStatus.valueOf(required("status", String::class.java)),
            eventId = get("event_id", Number::class.java)?.toLong(),
            createdAt = required("created_at", OffsetDateTime::class.java)
        )

    private fun <T : Any> Readable.required(
        column: String,
        type: Class<T>
    ): T = requireNotNull(get(column, type)) { "Column '$column' is missing from the instagram_draft projection" }

    private companion object {
        const val COLUMNS =
            "d.id, d.venue_id, v.slug AS venue_slug, v.name AS venue_name, d.media_id, d.permalink, d.posted_at, d.caption, " +
                "d.event_date, d.start_time, d.title, d.acts, d.price_presale, d.price_box_office, d.evidence, d.confidence, " +
                "d.status, d.event_id, d.created_at"
        const val FROM = "$EVENTS_SCHEMA.instagram_draft d JOIN $EVENTS_SCHEMA.venue v ON v.id = d.venue_id"
    }
}
