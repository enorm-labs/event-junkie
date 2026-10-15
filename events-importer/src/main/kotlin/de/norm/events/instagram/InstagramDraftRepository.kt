package de.norm.events.instagram

import de.norm.events.EVENTS_SCHEMA
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.awaitSingle
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.flow
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime

/** Where a draft stands. Only a person moves it out of [PENDING] (ADR-048 rule 5). */
enum class DraftStatus { PENDING, APPROVED, REJECTED, NOT_AN_EVENT }

/** A venue whose Instagram account is read: a row of `instagram_venue`. */
data class InstagramVenue(
    val id: Long,
    val slug: String,
    val name: String,
    val instagramUrl: String?
)

/** One row to insert into `instagram_draft`. A [DraftStatus.NOT_AN_EVENT] row carries no event and no caption. */
data class NewDraft(
    val venueId: Long,
    val post: InstagramPost,
    val status: DraftStatus,
    val event: DraftedEvent? = null,
    val model: String? = null
)

/**
 * The `instagram_venue` and `instagram_draft` tables, in hand-written SQL like `EventSuggestionRepository`:
 * the venue list joins `venue`, which this module has no entity for.
 */
@Repository
class InstagramDraftRepository(
    private val template: R2dbcEntityTemplate
) {
    suspend fun findVenues(): List<InstagramVenue> =
        template.databaseClient
            .sql(
                "SELECT v.id, v.slug, v.name, v.instagram_url FROM $EVENTS_SCHEMA.instagram_venue iv " +
                    "JOIN $EVENTS_SCHEMA.venue v ON v.id = iv.venue_id ORDER BY v.slug"
            ).map { row, _ ->
                InstagramVenue(
                    id = (row.get("id", Number::class.java) ?: error("venue id is missing")).toLong(),
                    slug = row.get("slug", String::class.java) ?: error("venue slug is missing"),
                    name = row.get("name", String::class.java) ?: error("venue name is missing"),
                    instagramUrl = row.get("instagram_url", String::class.java)
                )
            }.flow()
            .toList()

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

    private fun <T : Any> DatabaseClient.GenericExecuteSpec.bindOrNull(
        name: String,
        value: T?,
        type: Class<T>
    ): DatabaseClient.GenericExecuteSpec = if (value == null) bindNull(name, type) else bind(name, value)
}
