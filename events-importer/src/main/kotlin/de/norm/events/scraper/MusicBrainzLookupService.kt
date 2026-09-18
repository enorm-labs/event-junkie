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
 * **Runs after an import commits, never inside it**, in the shape of [DescriptionTranslationService]
 * and for the same reason: a verdict is derived, losing one costs a retry, and a service that is
 * slow or down must not fail a scrape. MusicBrainz not answering is a counter and the next run's
 * problem, never a `FAILED` source.
 *
 * **Every run also drains the backfill.** The rows the import touched come first; the slice is then
 * filled up to [MusicBrainzProperties.maxPerRun] with the oldest rows nobody has looked at, so the
 * 5,700 rows either cluster started with are checked inside the sweep's own pace rather than by a
 * separate job. `importer.musicbrainz.unchecked` shows it draining, and stays at zero afterwards.
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
     * Looks up what this run owes: the touched rows without a current verdict, then the backfill.
     *
     * @return how many verdicts were stored. Zero when the lookup is disabled, when nothing is owed,
     *   or when MusicBrainz was unavailable before the first row — one answer, because none of the
     *   three is a failure the caller can act on.
     */
    suspend fun lookupFor(
        source: EventSourceEntity,
        touchedArtistIds: Set<Long>
    ): Int {
        if (!properties.enabled) return 0
        val candidates = candidatesFor(touchedArtistIds)
        return if (candidates.isEmpty()) 0 else storeVerdicts(source, candidates)
    }

    /** One lookup per row until MusicBrainz stops answering; the rows after that wait for the next run. */
    private suspend fun storeVerdicts(
        source: EventSourceEntity,
        candidates: List<ArtistEntity>
    ): Int {
        var stored = 0
        for (artist in candidates) {
            val verdictStored =
                try {
                    lookup(artist)
                } catch (e: MusicBrainzUnavailableException) {
                    metrics.recordMusicBrainzLookup(STATE_ERROR)
                    logger.warn { "MusicBrainz unavailable after $stored of ${candidates.size} lookup(s) for '${source.slug}': ${e.message}" }
                    break
                }
            if (verdictStored) stored++
        }
        logger.info { "Stored $stored MusicBrainz verdict(s) of ${candidates.size} owed after '${source.slug}'" }
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
        if (verdict.match == MusicBrainzMatch.NONE) reportHead(artist.name)
        artistRepository.storeMusicBrainzVerdict(id, verdict.match.name, verdict.musicbrainzId)
        return true
    }

    /** The head pass of ADR-031: reported, counted, and not stored in this step. */
    private suspend fun reportHead(name: String) {
        val head = MusicBrainzMatcher.headOf(name) ?: return
        val verdict = MusicBrainzMatcher.decide(head, client.search(head))
        metrics.recordMusicBrainzHead(verdict.match.name.lowercase())
        logger.info { "Head '$head' of '$name' is ${verdict.match} in MusicBrainz${verdict.musicbrainzId?.let { " ($it)" }.orEmpty()}" }
    }

    private companion object {
        const val STATE_ERROR = "error"
    }
}
