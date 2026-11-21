package de.norm.events.artist

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** Rejects a wrong EXACT MusicBrainz match (V271). Apart from [ArtistService], which is at detekt's function cap. */
@Service
class MusicBrainzRejectionService(
    private val artistRepository: ArtistRepository,
    private val rejectionStore: MusicBrainzRejectionStore
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Rejects the EXACT MusicBrainz match of artist [id]: records its MBID as rejected, so the lookup sweep never
     * picks it for this row again, and clears what the enrichment copied from it. The row is AMBIGUOUS afterwards,
     * in the `/musicbrainz-review` queue. A row with no EXACT match changes nothing.
     *
     * @throws ArtistNotFoundException if no artist with the given [id] exists.
     */
    @Transactional
    suspend fun reject(id: Long): ArtistResponse {
        val existing = artistRepository.findById(id) ?: throw ArtistNotFoundException(id)
        val mbid = existing.musicbrainzId?.takeIf { existing.musicbrainzMatch == MusicBrainzMatch.EXACT.name }
        if (mbid == null) return ArtistResponse.fromDomain(existing.toDomain())
        rejectionStore.add(id, mbid)
        rejectionStore.clearMatch(id)
        logger.info { "Rejected the MusicBrainz match $mbid of artist $id: the lookup never picks it for this row again" }
        return ArtistResponse.fromDomain(requireNotNull(artistRepository.findById(id)).toDomain())
    }
}
