package de.norm.events.scraper

import de.norm.events.artist.ArtistEntity
import de.norm.events.artist.ArtistRepository
import de.norm.events.artist.MusicBrainzMatch
import de.norm.events.musicbrainz.MusicBrainzClient
import de.norm.events.musicbrainz.MusicBrainzMatcher
import de.norm.events.musicbrainz.MusicBrainzProperties
import de.norm.events.musicbrainz.MusicBrainzUnavailableException
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.toList
import org.springframework.stereotype.Service

/**
 * Looks every artist an import billed up in MusicBrainz, and stores the verdict (ADR-031, step B).
 *
 * **Runs on [ArtistLookupSweep]'s tick, never inside an import** (#2051): a verdict is derived,
 * losing one costs a retry, and a service that is slow or down must not fail a scrape or hold a
 * source in `RUNNING`. MusicBrainz not answering is a counter and the next tick's problem.
 *
 * **Every tick also drains the backfill.** The rows imports touched since the last tick come first;
 * the slice is then filled up to [MusicBrainzProperties.maxPerRun] with the oldest rows nobody has
 * looked at. `importer.musicbrainz.unchecked` shows it draining, and stays at zero afterwards. The
 * tick is the only caller and never overlaps itself, so no lock guards the slice (#1604).
 *
 * **Nothing but the three columns is written.** The name is never rewritten from a verdict, and
 * the head pass — the part before ` - ` or `: ` of a name MusicBrainz did not know — is a log line
 * and a counter for #1145 to read, not a row.
 */
@Service
class MusicBrainzLookupService(
    private val artistRepository: ArtistRepository,
    private val client: MusicBrainzClient,
    private val properties: MusicBrainzProperties,
    private val metrics: ImporterMetrics
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Looks up what this tick owes: the touched rows without a current verdict, then the backfill.
     *
     * @return how many verdicts were stored. Zero when the lookup is disabled, when nothing is owed,
     *   or when MusicBrainz was unavailable before the first row — one answer, because none of the
     *   three is a failure the caller can act on.
     */
    suspend fun sweep(touchedArtistIds: Set<Long>): Int {
        if (!properties.enabled) return 0
        val candidates = candidatesFor(touchedArtistIds)
        return if (candidates.isEmpty()) 0 else storeVerdicts(candidates)
    }

    /**
     * One lookup per row. A row MusicBrainz will not answer for is counted and left for the next
     * run; the run itself stops only after [STOP_AFTER_CONSECUTIVE_FAILURES] such rows in a row,
     * which is an outage rather than a burst (#1610).
     */
    private suspend fun storeVerdicts(candidates: List<ArtistEntity>): Int {
        var stored = 0
        var consecutiveFailures = 0
        for (artist in candidates) {
            try {
                if (lookup(artist)) stored++
                consecutiveFailures = 0
            } catch (e: MusicBrainzUnavailableException) {
                metrics.recordMusicBrainzLookup(STATE_ERROR)
                consecutiveFailures++
                logger.warn { "MusicBrainz unavailable for '${artist.name}' ($consecutiveFailures in a row): ${e.message}" }
                if (consecutiveFailures >= STOP_AFTER_CONSECUTIVE_FAILURES) break
            }
        }
        logger.info { "Stored $stored MusicBrainz verdict(s) of ${candidates.size} owed" }
        return stored
    }

    /** The touched rows that owe a verdict, then the oldest unchecked rows, bounded together. */
    private suspend fun candidatesFor(touchedArtistIds: Set<Long>): List<ArtistEntity> {
        val touched =
            if (touchedArtistIds.isEmpty()) {
                emptyList()
            } else {
                artistRepository.findNeedingMusicBrainzLookup(touchedArtistIds).toList().take(properties.maxPerRun)
            }
        val room = properties.maxPerRun - touched.size
        if (room <= 0) return touched
        val touchedIds = touched.mapTo(mutableSetOf()) { it.id }
        val backlog = artistRepository.findUncheckedByMusicBrainz(room + touched.size).toList().filterNot { it.id in touchedIds }
        return touched + backlog.take(room)
    }

    private suspend fun lookup(artist: ArtistEntity): Boolean {
        val id = artist.id ?: return false
        val verdict = MusicBrainzMatcher.decide(artist.name, client.search(artist.name))
        metrics.recordMusicBrainzLookup(verdict.match.name.lowercase())
        artistRepository.storeMusicBrainzVerdict(id, verdict.match.name, verdict.musicbrainzId)
        if (verdict.match == MusicBrainzMatch.NONE) reportHead(artist.name)
        return true
    }

    /**
     * The head pass of ADR-031: reported, counted, and not stored in this step. It runs after the
     * verdict is stored and swallows its own outage, so a burst here costs a log line, never a row.
     */
    private suspend fun reportHead(name: String) {
        val head = MusicBrainzMatcher.headOf(name) ?: return
        val candidates =
            try {
                client.search(head)
            } catch (e: MusicBrainzUnavailableException) {
                metrics.recordMusicBrainzHead(STATE_ERROR)
                logger.info { "Head '$head' of '$name' not looked up: ${e.message}" }
                return
            }
        val verdict = MusicBrainzMatcher.decide(head, candidates)
        metrics.recordMusicBrainzHead(verdict.match.name.lowercase())
        logger.info { "Head '$head' of '$name' is ${verdict.match} in MusicBrainz${verdict.musicbrainzId?.let { " ($it)" }.orEmpty()}" }
    }

    private companion object {
        const val STATE_ERROR = "error"
        const val STOP_AFTER_CONSECUTIVE_FAILURES = 3
    }
}
