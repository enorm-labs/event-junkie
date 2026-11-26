package de.norm.events.event

import de.norm.events.EVENTS_SCHEMA
import kotlinx.coroutines.flow.Flow
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository

/**
 * Reactive read repository for [EventEntity]. Dynamic search, calendar and today go through
 * [EventSearchRepository], since every ordered list shares one tiebreak; the derived method here
 * covers the slug lookup.
 */
interface EventRepository : CoroutineCrudRepository<EventEntity, Long> {
    /** Finds a single event by its unique slug, or null if not found. */
    suspend fun findBySlug(slug: String): EventEntity?

    /** Finds the event that [slug] is an alias of: a slug it had before its title, date or venue changed (#5560). */
    @Query(
        "SELECT e.* FROM $EVENTS_SCHEMA.event e " +
            "JOIN $EVENTS_SCHEMA.event_slug_alias alias ON alias.event_id = e.id WHERE alias.slug = :slug"
    )
    suspend fun findByAliasSlug(slug: String): EventEntity?
}

/**
 * Reactive read repository for the `event_artist` join table.
 */
interface EventArtistRepository : CoroutineCrudRepository<EventArtistEntity, Long> {
    /** Fetches artist associations for a single event. */
    fun findByEventId(eventId: Long): Flow<EventArtistEntity>

    /** Batch-fetches artist associations for multiple events to avoid N+1 queries. */
    fun findByEventIdIn(eventIds: Collection<Long>): Flow<EventArtistEntity>
}

/**
 * Reactive read repository for the `event_promoter` join table.
 */
interface EventPromoterRepository : CoroutineCrudRepository<EventPromoterEntity, Long> {
    /** Fetches promoter associations for a single event. */
    fun findByEventId(eventId: Long): Flow<EventPromoterEntity>

    /** Batch-fetches promoter associations for multiple events to avoid N+1 queries. */
    fun findByEventIdIn(eventIds: Collection<Long>): Flow<EventPromoterEntity>
}
