package de.norm.events.artist

import de.norm.events.EVENTS_SCHEMA
import de.norm.events.common.required
import de.norm.events.common.string
import kotlinx.coroutines.flow.toList
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.flow
import org.springframework.stereotype.Repository

/** An artist whose MusicBrainz verdict a person still has to decide. */
data class AmbiguousArtist(
    val id: Long,
    val name: String,
    val slug: String
)

/**
 * Every row with the verdict AMBIGUOUS, whatever its dates, for the review of the acts visitors wait for (#5214). Its own
 * store, because [ArtistRepository] and [ArtistService] are at detekt's function cap.
 */
@Repository
class AmbiguousArtistStore(
    private val databaseClient: DatabaseClient
) {
    /** By name, then id, so two rows of one name keep their order. */
    suspend fun all(): List<AmbiguousArtist> =
        databaseClient
            .sql("SELECT id, name, slug FROM $EVENTS_SCHEMA.artist WHERE musicbrainz_match = 'AMBIGUOUS' ORDER BY name, id")
            .map { row, _ -> AmbiguousArtist(row.required("id", Long::class.javaObjectType), row.string("name"), row.string("slug")) }
            .flow()
            .toList()
}
