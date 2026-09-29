package de.norm.events.scraper

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap

/**
 * Runs the artist lookups on their own tick: the MusicBrainz lookup, then its enrichment, then the
 * Discogs lookup (#2051).
 *
 * **Separate from the import on purpose.** Run after each import, the three passes turned a
 * one-second scrape into minutes, concurrent imports raced for the backfill, and a rollout killed
 * them mid-pass. Here one caller drains them at the pace the APIs allow, and a rollout loses at most
 * one tick. The order stays: a verdict reached in a tick is enriched, or asked of Discogs, in it.
 *
 * **Imports hand over the artists they touched.** A new row is queued by its `UNCHECKED` verdict
 * anyway, but a renamed one only by this hand-off. The set lives in memory, so a restart loses it
 * and a renamed row waits for its next import.
 */
@Service
class ArtistLookupSweep(
    private val musicBrainzLookupService: MusicBrainzLookupService,
    private val musicBrainzEnrichmentService: MusicBrainzEnrichmentService,
    private val discogsLookupService: DiscogsLookupService,
    @Value($$"${app.artists.lookup-enabled:true}")
    private val enabled: Boolean = true
) {
    private val logger = KotlinLogging.logger {}
    private val touched: MutableSet<Long> = ConcurrentHashMap.newKeySet()

    /** Queues the artists an import touched for the next tick. Nothing is queued while the tick is off. */
    fun queue(artistIds: Set<Long>) {
        if (enabled) touched.addAll(artistIds)
    }

    /**
     * `fixedDelay`, so a slow tick delays the next rather than overlapping it: the passes need no lock.
     * Spring blocks a scheduler thread for the whole tick, which is why the pool has more than one.
     */
    @Scheduled(fixedDelayString = $$"${app.artists.lookup-tick-millis:300000}")
    @Suppress("TooGenericExceptionCaught") // Intentional: an exception escaping cancels the @Scheduled task for the life of the process
    suspend fun tick() {
        if (!enabled) return
        try {
            sweep()
        } catch (e: Exception) {
            logger.error(e) { "Artist lookup tick failed" }
        }
    }

    /** One tick's work. Each pass is guarded alone, so MusicBrainz being down does not stop Discogs. */
    suspend fun sweep() {
        val ids = takeQueued()
        logger.debug { "Artist lookup tick with ${ids.size} touched artist(s)" }
        runCatching { musicBrainzLookupService.sweep(ids) }
            .onFailure { logger.warn(it) { "MusicBrainz lookup pass failed" } }
        runCatching { musicBrainzEnrichmentService.sweep(ids) }
            .onFailure { logger.warn(it) { "MusicBrainz enrichment pass failed" } }
        runCatching { discogsLookupService.sweep(ids) }
            .onFailure { logger.warn(it) { "Discogs lookup pass failed" } }
    }

    private fun takeQueued(): Set<Long> = touched.toSet().also { touched.removeAll(it) }
}
