package de.norm.events.event

import de.norm.events.EVENTS_SCHEMA
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.awaitFirstOrNull
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.security.MessageDigest
import java.util.HexFormat

/**
 * Moves `event.content_changed_at` when, and only when, the event page would read differently (#2768).
 * The sitemap's `lastmod` is that column. `updated_at` moves on every write, and Google discounts a
 * `lastmod` that moves without a change.
 *
 * Runs after the row and its join tables are written, so the hash is of what is stored: a kept
 * lineup, a pinned field and a detail-less row all hash as the page shows them. A row whose hash
 * still matches is not written.
 */
@Service
class EventContentStamp(
    private val eventArtistRepository: EventArtistRepository,
    private val eventPromoterRepository: EventPromoterRepository,
    private val template: R2dbcEntityTemplate
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Hashes each of [events] with its stored lineup and promoters, and writes the hash where it
     * differs from [EventEntity.contentHash]. The stamp moves to the transaction's `now()` for a new
     * row and for a changed one. A row with no hash yet only takes the hash: V116 backfilled its date.
     *
     * @param events the rows as written, carrying the hash and stamp they were read with.
     * @return how many stamps moved.
     */
    suspend fun restamp(events: Collection<EventEntity>): Int {
        val writes = writesFor(events.filter { it.id != null })
        if (writes.isNotEmpty()) store(writes)
        val moved = writes.count { it.moved }
        logger.debug { "Content stamp: $moved moved, ${writes.size - moved} hashed for the first time, of ${events.size} event(s)" }
        return moved
    }

    /** The rows whose hash differs from the stored one, each with whether its stamp moves. */
    private suspend fun writesFor(events: List<EventEntity>): List<Write> {
        if (events.isEmpty()) return emptyList()
        val ids = events.map { requireNotNull(it.id) }
        val lineups = eventArtistRepository.findByEventIdIn(ids).toList().groupBy { it.eventId }
        val promoters = eventPromoterRepository.findByEventIdIn(ids).toList().groupBy({ it.eventId }, { it.promoterId })
        return events.mapNotNull { event ->
            val id = requireNotNull(event.id)
            val hash = hash(event, lineups[id].orEmpty(), promoters[id].orEmpty())
            when {
                event.contentChangedAt == null -> Write(id, hash, moved = true)
                event.contentHash == null -> Write(id, hash, moved = false)
                event.contentHash != hash -> Write(id, hash, moved = true)
                else -> null
            }
        }
    }

    private suspend fun store(writes: List<Write>) {
        template.databaseClient
            .sql(
                "UPDATE $EVENTS_SCHEMA.event e SET content_hash = s.hash, " +
                    "content_changed_at = CASE WHEN s.moved THEN now() ELSE e.content_changed_at END " +
                    "FROM unnest(:ids::bigint[], :hashes::text[], :moved::boolean[]) AS s(id, hash, moved) WHERE e.id = s.id"
            ).bind("ids", writes.map { it.id }.toTypedArray())
            .bind("hashes", writes.map { it.hash }.toTypedArray())
            .bind("moved", writes.map { it.moved }.toTypedArray())
            .fetch()
            .rowsUpdated()
            .awaitFirstOrNull()
    }

    private data class Write(
        val id: Long,
        val hash: String,
        val moved: Boolean
    )

    companion object {
        /**
         * SHA-256 of what the event page shows. In: the title, subtitle and description, the type, the
         * status and where it moved to, the dates and times, the venue and room, the prices, the sold-out
         * and free flags, the ticket link, the lineup with its roles, order, stages and set times, and
         * the promoters.
         *
         * Out, on purpose: the image, whose URL some sources rotate while the picture stays; the
         * translation, which is derived from the description and arrives after the import; the source
         * and Facebook links; the genre, which is a filter more than page text. A changed field list
         * changes every hash, so the migration that changes it sets `content_hash` to NULL.
         */
        fun hash(
            event: EventEntity,
            lineup: List<EventArtistEntity>,
            promoterIds: List<Long>
        ): String {
            val columns =
                listOf(
                    event.title,
                    event.subtitle,
                    event.description,
                    event.eventType,
                    event.status,
                    event.relocatedTo,
                    event.eventDate,
                    event.doorsTime,
                    event.startTime,
                    event.endDate,
                    event.endTime,
                    event.venueId,
                    event.room,
                    event.pricePresale.plain(),
                    event.priceBoxOffice.plain(),
                    event.priceCurrency,
                    event.priceNote,
                    event.soldOut,
                    event.free,
                    event.ticketUrl
                )
            val acts =
                lineup
                    .sortedWith(compareBy({ it.billingOrder }, { it.artistId }))
                    .map { act -> listOf(act.artistId, act.role, act.billingOrder, act.stage, act.setStart, act.setEnd).joinToString("") { it.encoded() } }
            val text = (columns + acts + listOf(promoterIds.sorted())).joinToString("") { it.encoded() }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)))
        }

        /** `12`, `12.0` and `12.00` are one price; the column has scale 2, and a built row may not yet. */
        private fun BigDecimal?.plain(): String? = this?.stripTrailingZeros()?.toPlainString()

        /** Length-prefixed, so no text can pass for a null or for two fields: `null` is `-`, `"ab"` is `2:ab`. */
        private fun Any?.encoded(): String = this?.toString()?.let { "${it.length}:$it" } ?: "-"
    }
}
