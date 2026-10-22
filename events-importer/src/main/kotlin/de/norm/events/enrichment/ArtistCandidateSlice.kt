package de.norm.events.enrichment

import de.norm.events.artist.ArtistEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList

/**
 * The rows one lookup pass owes: the touched ones first, then the backfill, together at most [limit].
 *
 * [touchedRows] reads the rows of [touchedArtistIds] that still owe the pass, [backfillRows] the
 * oldest rows nobody has asked about, up to the count it is given. A row in both is a touched row,
 * counted once.
 */
internal suspend fun artistCandidateSlice(
    touchedArtistIds: Set<Long>,
    limit: Int,
    touchedRows: (Collection<Long>) -> Flow<ArtistEntity>,
    backfillRows: (Int) -> Flow<ArtistEntity>
): List<ArtistEntity> {
    val touched = if (touchedArtistIds.isEmpty()) emptyList() else touchedRows(touchedArtistIds).toList().take(limit)
    val room = limit - touched.size
    if (room <= 0) return touched
    val touchedIds = touched.mapTo(mutableSetOf()) { it.id }
    val backfill = backfillRows(room + touched.size).toList().filterNot { it.id in touchedIds }
    return touched + backfill.take(room)
}
