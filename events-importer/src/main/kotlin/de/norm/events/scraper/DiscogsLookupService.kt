package de.norm.events.scraper

import de.norm.events.artist.ArtistDiscogsRepository
import de.norm.events.artist.ArtistEntity
import de.norm.events.discogs.DiscogsClient
import de.norm.events.discogs.DiscogsMatcher
import de.norm.events.discogs.DiscogsProperties
import de.norm.events.discogs.DiscogsUnavailableException
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.annotation.PostConstruct
import kotlinx.coroutines.flow.toList
import org.springframework.stereotype.Service

/**
 * Asks Discogs about every artist MusicBrainz does not know, and stores the verdict (#2026, ADR-035).
 *
 * **Only a row whose MusicBrainz verdict is `NONE`.** MusicBrainz stays the hub (ADR-031). An EXACT
 * row gets its Discogs link from MusicBrainz's own relationships, and Discogs cannot settle an
 * AMBIGUOUS one, because its search returns no country to break the tie. So this runs last on
 * [ArtistLookupSweep]'s tick, after the MusicBrainz lookup, and for the same reasons: a verdict is
 * derived, and Discogs being slow or down must not fail a scrape.
 *
 * **The same queue as MusicBrainz's.** The touched rows first, then the oldest unasked `NONE` rows
 * up to [DiscogsProperties.maxPerRun]. `importer.discogs.unchecked` shows it draining.
 *
 * **An id, a verdict and an empty link filled — nothing else.** Discogs' API terms forbid storing
 * its content longer than a service needs it and showing it more than six hours stale. The name and
 * the MusicBrainz verdict are never rewritten from a Discogs verdict.
 */
@Service
class DiscogsLookupService(
    private val artistRepository: ArtistDiscogsRepository,
    private val client: DiscogsClient,
    private val properties: DiscogsProperties,
    private val metrics: ImporterMetrics
) {
    private val logger = KotlinLogging.logger {}

    /** Says once, at start-up, why the sweep will send nothing. A switched-off lookup is a decision, not a fault. */
    @PostConstruct
    fun reportInactive() {
        if (properties.enabled && !properties.active) {
            logger.info { "Discogs lookup is off: app.discogs.consumer-key or consumer-secret is not set" }
        }
    }

    /**
     * Looks up what this tick owes: the touched `NONE` rows without a current Discogs verdict, then
     * the backfill.
     *
     * @return how many verdicts were stored. Zero when the lookup is off, when nothing is owed, or
     *   when Discogs was unavailable before the first row.
     */
    suspend fun sweep(touchedArtistIds: Set<Long>): Int {
        if (!properties.active) return 0
        val candidates = candidatesFor(touchedArtistIds)
        return if (candidates.isEmpty()) 0 else storeVerdicts(candidates)
    }

    /**
     * The `NONE` rows Discogs has not been asked about, for `importer.discogs.unchecked`. Zero while the
     * lookup is off: a switched-off lookup owes nothing, and every `NONE` row would read as a backlog
     * that `ej-discogs-backlog-stuck` fires on (#2043).
     */
    suspend fun backlog(): Long = if (properties.active) artistRepository.countUncheckedByDiscogs() else 0

    /** One lookup per row; the run stops after [STOP_AFTER_CONSECUTIVE_FAILURES] unavailable rows in a row. */
    private suspend fun storeVerdicts(candidates: List<ArtistEntity>): Int {
        var stored = 0
        var consecutiveFailures = 0
        for (artist in candidates) {
            try {
                if (lookup(artist)) stored++
                consecutiveFailures = 0
            } catch (e: DiscogsUnavailableException) {
                metrics.recordDiscogsLookup(STATE_ERROR)
                consecutiveFailures++
                logger.warn(e) { "Discogs unavailable for '${artist.name}' ($consecutiveFailures in a row)" }
                if (consecutiveFailures >= STOP_AFTER_CONSECUTIVE_FAILURES) break
            }
        }
        logger.info { "Stored $stored Discogs verdict(s) of ${candidates.size} owed" }
        return stored
    }

    /** The touched rows that owe a verdict, then the oldest unasked rows, bounded together. */
    private suspend fun candidatesFor(touchedArtistIds: Set<Long>): List<ArtistEntity> {
        val touched =
            if (touchedArtistIds.isEmpty()) {
                emptyList()
            } else {
                artistRepository.findNeedingDiscogsLookup(touchedArtistIds).toList().take(properties.maxPerRun)
            }
        val room = properties.maxPerRun - touched.size
        if (room <= 0) return touched
        val touchedIds = touched.mapTo(mutableSetOf()) { it.id }
        val backlog = artistRepository.findUncheckedByDiscogs(room + touched.size).toList().filterNot { it.id in touchedIds }
        return touched + backlog.take(room)
    }

    private suspend fun lookup(artist: ArtistEntity): Boolean {
        val id = artist.id ?: return false
        val verdict = DiscogsMatcher.decide(artist.name, client.search(artist.name))
        metrics.recordDiscogsLookup(verdict.match.name.lowercase())
        artistRepository.storeDiscogsVerdict(id, verdict.match.name, verdict.discogsId, verdict.discogsUrl)
        return true
    }

    private companion object {
        const val STATE_ERROR = "error"
        const val STOP_AFTER_CONSECUTIVE_FAILURES = 3
    }
}
