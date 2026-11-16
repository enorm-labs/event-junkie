package de.norm.events.account

import de.norm.events.EVENTS_SCHEMA
import kotlinx.coroutines.flow.toList
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.await
import org.springframework.r2dbc.core.awaitRowsUpdated
import org.springframework.r2dbc.core.flow
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** A watch as the Following page and the export show it. */
data class Watch(
    val mbid: String,
    val name: String,
    val notify: String,
    val watchedAt: Instant
)

/** One row of a conversion: a watch, the artist row it became, and an upcoming event of that artist when there is one. */
data class ConvertedWatch(
    val watchId: UUID,
    val accountId: UUID,
    val artistId: Long,
    val announced: NewDate?,
    val push: Boolean
)

/**
 * The `kind = 'watch'` rows of `accounts.follow` (V259, #5214): an act followed by its MusicBrainz id before it has an
 * artist row. [convert] turns each into an artist follow once a row with that id has the verdict EXACT.
 */
@Component
class WatchStore(
    private val db: DatabaseClient
) {
    suspend fun list(accountId: UUID): List<Watch> =
        db
            .sql("SELECT mbid, name, notify, created_at FROM accounts.follow WHERE account_id = :account AND kind = 'watch' ORDER BY lower(name), created_at")
            .bind("account", accountId)
            .map { row ->
                Watch(
                    mbid = row.required("mbid", String::class.java),
                    name = row.required("name", String::class.java),
                    notify = row.required("notify", String::class.java),
                    watchedAt = row.required("created_at", Instant::class.java)
                )
            }.flow()
            .toList()

    /** Watches [mbid] with the bell on, or leaves an existing watch as it is. */
    suspend fun watch(
        accountId: UUID,
        mbid: String,
        name: String,
        now: Instant
    ) {
        db
            .sql(
                "INSERT INTO accounts.follow (account_id, kind, mbid, name, notify, created_at) VALUES (:account, 'watch', :mbid, :name, 'instant', :now) " +
                    "ON CONFLICT (account_id, mbid) WHERE kind = 'watch' DO NOTHING"
            ).bind("account", accountId)
            .bind("mbid", mbid)
            .bind("name", name)
            .bind("now", now)
            .await()
    }

    /** Answers false when the account does not watch [mbid]. */
    suspend fun unwatch(
        accountId: UUID,
        mbid: String
    ): Boolean =
        db
            .sql("DELETE FROM accounts.follow WHERE account_id = :account AND kind = 'watch' AND mbid = :mbid")
            .bind("account", accountId)
            .bind("mbid", mbid)
            .fetch()
            .awaitRowsUpdated() > 0

    /** The slug of an artist row with the verdict EXACT for each of [mbids] that has one. The lowest id wins a shared id. */
    suspend fun artistSlugs(mbids: Collection<String>): Map<String, String> {
        if (mbids.isEmpty()) return emptyMap()
        return db
            .sql(
                "SELECT DISTINCT ON (musicbrainz_id) musicbrainz_id, slug FROM $EVENTS_SCHEMA.artist " +
                    "WHERE musicbrainz_match = 'EXACT' AND musicbrainz_id = ANY(:mbids) ORDER BY musicbrainz_id, id"
            ).bind("mbids", mbids.toTypedArray())
            .map { row -> row.required("musicbrainz_id", String::class.java) to row.required("slug", String::class.java) }
            .flow()
            .toList()
            .toMap()
    }

    /**
     * Turns every watch whose MusicBrainz id an EXACT artist row now carries into an artist follow with the same bell, and
     * writes a notification for each upcoming event of that artist. One statement, so a failure changes nothing, and
     * `SKIP LOCKED` hands each watch to one replica. Two rows with one id give two follows.
     *
     * The event's `created_at` plays no part: the verdict comes on a tick after the import, so the event is usually older
     * than the new-dates job's cursor by the time its artist is known.
     *
     * One row per watch and artist row, and per announced event. [ConvertedWatch.push] is true where the bell is on and
     * the account has a browser subscribed.
     */
    suspend fun convert(
        today: LocalDate,
        now: Instant
    ): List<ConvertedWatch> =
        db
            .sql(
                """
                WITH matched AS (
                    SELECT w.id, w.account_id, w.notify, a.id AS artist_id
                    FROM accounts.follow w JOIN $EVENTS_SCHEMA.artist a ON a.musicbrainz_id = w.mbid AND a.musicbrainz_match = 'EXACT'
                    WHERE w.kind = 'watch'
                    FOR UPDATE OF w SKIP LOCKED
                ), gone AS (
                    DELETE FROM accounts.follow WHERE id IN (SELECT id FROM matched)
                ), followed AS (
                    INSERT INTO accounts.follow (account_id, kind, entity_id, notify, created_at)
                    SELECT account_id, 'artist', artist_id, notify, :now FROM matched
                    ON CONFLICT (account_id, kind, entity_id) WHERE kind <> 'search' DO NOTHING
                ), announced AS (
                    INSERT INTO accounts.notification (account_id, event_id, kind, about_kind, about_id, created_at)
                    SELECT DISTINCT ON (m.account_id, e.id) m.account_id, e.id, 'new_date', 'artist', m.artist_id, :now
                    FROM matched m
                    JOIN $EVENTS_SCHEMA.event_artist ea ON ea.artist_id = m.artist_id
                    JOIN $EVENTS_SCHEMA.event e ON e.id = ea.event_id
                    WHERE e.event_date >= :today AND e.status <> 'CANCELLED'
                    ON CONFLICT (account_id, event_id, kind) DO NOTHING
                    RETURNING account_id, event_id, about_id
                )
                SELECT m.id, m.account_id, m.artist_id, m.notify, acc.locale, e.slug, e.title, e.event_date,
                    EXISTS (SELECT 1 FROM accounts.push_subscription s WHERE s.account_id = m.account_id) AS subscribed
                FROM matched m
                JOIN accounts.account acc ON acc.id = m.account_id
                LEFT JOIN announced n ON n.account_id = m.account_id AND n.about_id = m.artist_id
                LEFT JOIN $EVENTS_SCHEMA.event e ON e.id = n.event_id
                ORDER BY m.account_id, e.event_date, e.id
                """.trimIndent()
            ).bind("today", today)
            .bind("now", now)
            .map { row ->
                val accountId = row.required("account_id", UUID::class.java)
                val slug = row.get("slug", String::class.java)
                ConvertedWatch(
                    watchId = row.required("id", UUID::class.java),
                    accountId = accountId,
                    artistId = row.required("artist_id", Long::class.javaObjectType),
                    announced =
                        slug?.let {
                            NewDate(
                                accountId = accountId,
                                locale = row.required("locale", String::class.java),
                                slug = it,
                                title = row.required("title", String::class.java),
                                date = row.required("event_date", LocalDate::class.java)
                            )
                        },
                    push = row.required("notify", String::class.java) == "instant" && row.required("subscribed", Boolean::class.javaObjectType)
                )
            }.flow()
            .toList()
}
