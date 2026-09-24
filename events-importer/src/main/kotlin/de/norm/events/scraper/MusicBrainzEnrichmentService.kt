package de.norm.events.scraper

import de.norm.events.artist.ArtistEnrichmentStore
import de.norm.events.artist.ArtistEntity
import de.norm.events.artist.ArtistRepository
import de.norm.events.musicbrainz.MusicBrainzArtist
import de.norm.events.musicbrainz.MusicBrainzClient
import de.norm.events.musicbrainz.MusicBrainzProperties
import de.norm.events.musicbrainz.MusicBrainzUnavailableException
import de.norm.events.wikimedia.CommonsImage
import de.norm.events.wikimedia.WikimediaClient
import de.norm.events.wikimedia.WikimediaUnavailableException
import de.norm.events.wikimedia.WikipediaExtract
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.sync.Mutex
import org.springframework.stereotype.Service

/**
 * Reads the MusicBrainz entity behind every EXACT verdict and fills what the row lacks: links, type,
 * for an ensemble when and where it formed, the Commons picture its Wikidata item names (ADR-031,
 * step C), and an ensemble's Wikipedia lead (step C+).
 *
 * Runs after [MusicBrainzLookupService], in the same guarded slot after an import commits, and for
 * the same reasons: derived data, a retry on failure, never a `FAILED` source. The touched rows come
 * first, then a slice of the backfill under a `tryLock` mutex so concurrent sweeps do not read the
 * same slice (#1604). Each row costs one MusicBrainz request and, when the entity links a Wikidata
 * item, two at Wikimedia for a missing picture and two for a missing ensemble description.
 *
 * [ArtistEnrichment] decides what is written; [ArtistEnrichmentStore] writes only that. A row whose
 * MBID is gone is stamped as read with nothing filled, so it is not asked for again until the
 * verdict changes.
 */
@Service
class MusicBrainzEnrichmentService(
    private val artistRepository: ArtistRepository,
    private val store: ArtistEnrichmentStore,
    private val musicBrainz: MusicBrainzClient,
    private val wikimedia: WikimediaClient,
    private val properties: MusicBrainzProperties,
    private val metrics: ImporterMetrics
) {
    private val logger = KotlinLogging.logger {}
    private val backfill = Mutex()

    /**
     * Reads what this run owes: the touched EXACT rows without a current read, then the backfill
     * when no other sweep is draining it.
     *
     * @return how many rows were stamped as read; zero when the lookup is disabled or nothing is owed.
     */
    suspend fun enrichFor(
        source: EventSourceEntity,
        touchedArtistIds: Set<Long>
    ): Int {
        if (!properties.enabled) return 0
        val drainsBackfill = backfill.tryLock()
        try {
            val candidates = candidatesFor(touchedArtistIds, drainsBackfill)
            return if (candidates.isEmpty()) 0 else enrich(source, candidates)
        } finally {
            if (drainsBackfill) backfill.unlock()
        }
    }

    private suspend fun enrich(
        source: EventSourceEntity,
        candidates: List<ArtistEntity>
    ): Int {
        var stored = 0
        var consecutiveFailures = 0
        for (artist in candidates) {
            val failure = readOrFailure(artist)
            if (failure == null) {
                stored++
                consecutiveFailures = 0
            } else {
                consecutiveFailures++
                metrics.recordMusicBrainzEnrichmentError()
                logger.warn(failure) { "Entity read for '${artist.name}' failed ($consecutiveFailures in a row) during '${source.slug}'" }
                if (consecutiveFailures >= STOP_AFTER_CONSECUTIVE_FAILURES) break
            }
        }
        logger.info { "Read $stored MusicBrainz entity(ies) of ${candidates.size} owed after '${source.slug}'" }
        return stored
    }

    /** Null when the row was read and stamped; the exception when MusicBrainz or Wikimedia would not answer. */
    private suspend fun readOrFailure(artist: ArtistEntity): Exception? =
        try {
            enrich(artist)
            null
        } catch (e: MusicBrainzUnavailableException) {
            e
        } catch (e: WikimediaUnavailableException) {
            e
        }

    private suspend fun candidatesFor(
        touchedArtistIds: Set<Long>,
        includeBackfill: Boolean
    ): List<ArtistEntity> {
        val limit = properties.enrichMaxPerRun
        val touched =
            if (touchedArtistIds.isEmpty()) {
                emptyList()
            } else {
                artistRepository.findNeedingMusicBrainzEnrichment(touchedArtistIds).toList().take(limit)
            }
        val room = limit - touched.size
        if (!includeBackfill || room <= 0) return touched
        val touchedIds = touched.mapTo(mutableSetOf()) { it.id }
        val backlog = artistRepository.findUnenrichedByMusicBrainz(room + touched.size).toList().filterNot { it.id in touchedIds }
        return touched + backlog.take(room)
    }

    private suspend fun enrich(artist: ArtistEntity) {
        val id = requireNotNull(artist.id) { "A candidate row is persisted" }
        val mbid = requireNotNull(artist.musicbrainzId) { "An EXACT row carries its MBID (V037)" }
        val entity = musicBrainz.artist(mbid)
        if (entity == null) {
            logger.info { "MusicBrainz no longer has $mbid for '${artist.name}'; nothing filled" }
            store.store(id, emptyMap())
        } else {
            val image = if (artist.imageUrl == null) pictureOf(entity) else null
            val wantsDescription = ArtistEnrichment.wantsDescription(artist, entity)
            val extract = if (wantsDescription) extractOf(artist, entity) else null
            val filled = ArtistEnrichment.fill(artist, entity, image, wikimedia.maxBytes, extract)
            store.store(id, filled.columns)
            filled.fields.forEach(metrics::recordMusicBrainzEnriched)
            filled.imageRefusal?.let { reason ->
                metrics.recordMusicBrainzImageRefused(reason)
                logger.info { "Commons picture for '${artist.name}' refused: $reason" }
            }
            val descriptionRefusal = filled.descriptionRefusal ?: NO_ARTICLE.takeIf { wantsDescription && extract == null }
            descriptionRefusal?.let { reason ->
                metrics.recordWikipediaDescriptionRefused(reason)
                logger.info { "Wikipedia description for '${artist.name}' refused: $reason" }
            }
            logger.debug { "Filled ${filled.fields} on '${artist.name}' from MusicBrainz $mbid" }
        }
    }

    private suspend fun pictureOf(entity: MusicBrainzArtist): CommonsImage? = ArtistEnrichment.wikidataIdOf(entity)?.let { wikimedia.imageFor(it) }

    private suspend fun extractOf(
        artist: ArtistEntity,
        entity: MusicBrainzArtist
    ): WikipediaExtract? = ArtistEnrichment.wikidataIdOf(entity)?.let { wikimedia.extractFor(it, WikipediaLead.languagesFor(artist.country ?: entity.country)) }

    private companion object {
        const val STOP_AFTER_CONSECUTIVE_FAILURES = 3
        const val NO_ARTICLE = "no-article"
    }
}
