package de.norm.events.account

import de.norm.events.EVENTS_SCHEMA
import de.norm.events.event.FollowedIds
import kotlinx.coroutines.flow.toList
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.await
import org.springframework.r2dbc.core.awaitOneOrNull
import org.springframework.r2dbc.core.awaitRowsUpdated
import org.springframework.r2dbc.core.awaitSingle
import org.springframework.r2dbc.core.flow
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

/**
 * The rows of `accounts.follow` (V195) for artists, venues and promoters. A follow holds the entity's id, so this
 * reads the slug and the name from `events` at every read, and a follow whose entity is gone drops out.
 */
@Component
class FollowStore(
    private val db: DatabaseClient
) {
    /** The id behind [slug], or null when no such entity exists. */
    suspend fun entityId(
        kind: FollowKind,
        slug: String
    ): Long? =
        db
            .sql("SELECT id FROM $EVENTS_SCHEMA.${kind.table} WHERE slug = :slug")
            .bind("slug", slug)
            .map { row -> row.required("id", Long::class.javaObjectType) }
            .awaitOneOrNull()

    suspend fun count(accountId: UUID): Long =
        db
            .sql("SELECT count(*) AS n FROM accounts.follow WHERE account_id = :account")
            .bind("account", accountId)
            .map { row -> row.required("n", Long::class.javaObjectType) }
            .awaitSingle()

    /** Follows, or leaves an existing follow as it is. */
    suspend fun follow(
        accountId: UUID,
        kind: FollowKind,
        entityId: Long,
        now: Instant
    ) {
        db
            .sql(
                "INSERT INTO accounts.follow (account_id, kind, entity_id, created_at) VALUES (:account, :kind, :entity, :now) " +
                    "ON CONFLICT (account_id, kind, entity_id) WHERE kind <> 'search' DO NOTHING"
            ).bind("account", accountId)
            .bind("kind", kind.tag)
            .bind("entity", entityId)
            .bind("now", now)
            .await()
    }

    suspend fun unfollow(
        accountId: UUID,
        kind: FollowKind,
        entityId: Long
    ) {
        db
            .sql("DELETE FROM accounts.follow WHERE account_id = :account AND kind = :kind AND entity_id = :entity")
            .bind("account", accountId)
            .bind("kind", kind.tag)
            .bind("entity", entityId)
            .await()
    }

    suspend fun follows(
        accountId: UUID,
        kind: FollowKind,
        entityId: Long
    ): Boolean =
        db
            .sql("SELECT 1 AS hit FROM accounts.follow WHERE account_id = :account AND kind = :kind AND entity_id = :entity")
            .bind("account", accountId)
            .bind("kind", kind.tag)
            .bind("entity", entityId)
            .map { _ -> true }
            .awaitOneOrNull() ?: false

    /** Sets the bell of one follow (#3894). Answers false when the account does not follow it. */
    suspend fun setNotify(
        accountId: UUID,
        kind: FollowKind,
        entityId: Long,
        notify: String
    ): Boolean =
        db
            .sql("UPDATE accounts.follow SET notify = :notify WHERE account_id = :account AND kind = :kind AND entity_id = :entity")
            .bind("notify", notify)
            .bind("account", accountId)
            .bind("kind", kind.tag)
            .bind("entity", entityId)
            .fetch()
            .awaitRowsUpdated() > 0

    /** Sets the bell of every follow of the account at once (#3894), as offered when notifications first go on. Answers how many. */
    suspend fun setAllNotify(
        accountId: UUID,
        notify: String
    ): Long =
        db
            .sql("UPDATE accounts.follow SET notify = :notify WHERE account_id = :account AND kind <> 'search'")
            .bind("notify", notify)
            .bind("account", accountId)
            .fetch()
            .awaitRowsUpdated()

    /** Everything the account follows whose entity still exists, by name. */
    suspend fun list(accountId: UUID): List<FollowedEntity> =
        db
            .sql(
                FollowKind.entries.joinToString(" UNION ALL ") { kind ->
                    "SELECT f.kind, x.slug, x.name, f.notify, f.created_at FROM accounts.follow f " +
                        "JOIN $EVENTS_SCHEMA.${kind.table} x ON x.id = f.entity_id WHERE f.account_id = :account AND f.kind = '${kind.tag}'"
                } + " ORDER BY name"
            ).bind("account", accountId)
            .map { row ->
                FollowedEntity(
                    kind = FollowKind.of(row.required("kind", String::class.java)),
                    slug = row.required("slug", String::class.java),
                    name = row.required("name", String::class.java),
                    notify = row.required("notify", String::class.java),
                    followedAt = row.required("created_at", Instant::class.java)
                )
            }.flow()
            .toList()

    /** The followed ids per kind, for the event search. */
    suspend fun followedIds(accountId: UUID): FollowedIds {
        val ids =
            db
                .sql("SELECT kind, entity_id FROM accounts.follow WHERE account_id = :account AND entity_id IS NOT NULL")
                .bind("account", accountId)
                .map { row -> row.required("kind", String::class.java) to row.required("entity_id", Long::class.javaObjectType) }
                .flow()
                .toList()
                .groupBy({ it.first }, { it.second })
        return FollowedIds(
            artistIds = ids[FollowKind.ARTIST.tag].orEmpty(),
            venueIds = ids[FollowKind.VENUE.tag].orEmpty(),
            promoterIds = ids[FollowKind.PROMOTER.tag].orEmpty()
        )
    }

    /** The follows of each kind, a kind nobody follows included. Kinds this code does not know yet are left out. */
    suspend fun countsByKind(): Map<FollowKind, Long> {
        val counted =
            db
                .sql("SELECT kind, count(*) AS n FROM accounts.follow GROUP BY kind")
                .map { row -> FollowKind.ofOrNull(row.required("kind", String::class.java)) to row.required("n", Long::class.javaObjectType) }
                .flow()
                .toList()
        return FollowKind.entries.associateWith { kind -> counted.filter { it.first == kind }.sumOf { it.second } }
    }

    /** Follows whose artist, venue or promoter no longer exists: deleted, or merged into another. */
    suspend fun deleteOrphans(): Long =
        FollowKind.entries.sumOf { kind ->
            db
                .sql(
                    "DELETE FROM accounts.follow f WHERE f.kind = :kind " +
                        "AND NOT EXISTS (SELECT 1 FROM $EVENTS_SCHEMA.${kind.table} x WHERE x.id = f.entity_id)"
                ).bind("kind", kind.tag)
                .fetch()
                .awaitRowsUpdated()
        }
}

/** What a visitor can follow today. A festival and a saved search are kinds the table knows, and #3890 adds the search. */
enum class FollowKind(
    val tag: String,
    val table: String
) {
    ARTIST("artist", "artist"),
    VENUE("venue", "venue"),
    PROMOTER("promoter", "promoter");

    companion object {
        /** The kind for a path segment or a stored tag, or null for anything else. */
        fun ofOrNull(tag: String): FollowKind? = entries.firstOrNull { it.tag == tag }

        fun of(tag: String): FollowKind = requireNotNull(ofOrNull(tag)) { "Unknown follow kind $tag" }
    }
}

/** A follow as the list and the export show it. */
data class FollowedEntity(
    val kind: FollowKind,
    val slug: String,
    val name: String,
    val notify: String,
    val followedAt: Instant
)
