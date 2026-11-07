package de.norm.events.venue

import de.norm.events.EVENTS_SCHEMA
import io.swagger.v3.oas.annotations.media.Schema
import kotlinx.coroutines.flow.toList
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.flow
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import kotlin.math.cos
import kotlin.math.roundToInt

/** A U-Bahn, S-Bahn or tram station near a venue (#2724). */
@Schema(description = "A U-Bahn, S-Bahn or tram station near the venue, from VBB's GTFS data (CC BY 4.0)")
data class NearbyStopResponse(
    @Schema(description = "The station's name as VBB publishes it, less a trailing \"(Berlin)\"", example = "S+U Warschauer Str.")
    val name: String,
    @Schema(description = "The S-Bahn, U-Bahn and tram lines that stop here, in that order", example = "[\"S3\", \"S5\", \"U1\", \"M10\"]")
    val lines: List<String>,
    @Schema(
        description = "About how far it is to walk, in metres: the straight line times 1.3, rounded to 10. Not a route.",
        example = "350"
    )
    val walkingMetres: Int
)

/**
 * Reads `transit_stop`, which the importer fills from VBB's GTFS feed once a quarter. The nearest stations are
 * computed per request, so a venue added between two imports has them at once.
 *
 * The walking distance is the straight line times [WALKING_FACTOR], the issue's choice over a self-hosted router.
 */
@Repository
class NearbyStopRepository(
    private val databaseClient: DatabaseClient
) {
    /** Up to [MAX_STOPS] stations within [MAX_WALKING_METRES] of the point, nearest first; empty without a point. */
    suspend fun nearest(
        latitude: BigDecimal?,
        longitude: BigDecimal?
    ): List<NearbyStopResponse> {
        if (latitude == null || longitude == null) return emptyList()
        val lat = latitude.toDouble()
        val lon = longitude.toDouble()
        val maxStraight = MAX_WALKING_METRES / WALKING_FACTOR
        val latSpan = maxStraight / METRES_PER_DEGREE
        val lonSpan = maxStraight / (METRES_PER_DEGREE * cos(Math.toRadians(lat)))
        return databaseClient
            .sql(
                """
                SELECT name, lines, metres FROM (
                    SELECT name, lines,
                           2 * $EARTH_RADIUS_METRES * asin(sqrt(
                               power(sin(radians(latitude::float8 - :lat) / 2), 2) +
                               cos(radians(:lat)) * cos(radians(latitude::float8)) * power(sin(radians(longitude::float8 - :lon) / 2), 2)
                           )) AS metres
                    FROM $EVENTS_SCHEMA.transit_stop
                    WHERE latitude BETWEEN :minLat AND :maxLat AND longitude BETWEEN :minLon AND :maxLon
                ) s
                WHERE metres <= :maxStraight
                ORDER BY metres, name
                LIMIT $MAX_STOPS
                """.trimIndent()
            ).bind("lat", lat)
            .bind("lon", lon)
            .bind("minLat", lat - latSpan)
            .bind("maxLat", lat + latSpan)
            .bind("minLon", lon - lonSpan)
            .bind("maxLon", lon + lonSpan)
            .bind("maxStraight", maxStraight)
            .map { row, _ ->
                NearbyStopResponse(
                    name = requireNotNull(row.get("name", String::class.java)) { "transit_stop.name is NOT NULL" },
                    lines = requireNotNull(row.get("lines", Array<String>::class.java)) { "transit_stop.lines is NOT NULL" }.toList(),
                    walkingMetres = walkingMetres(requireNotNull(row.get("metres", Number::class.java)).toDouble())
                )
            }.flow()
            .toList()
    }

    companion object {
        const val MAX_STOPS = 3

        /** About 20 minutes on foot. A venue farther from every station shows none rather than a long walk. */
        const val MAX_WALKING_METRES = 1500.0

        /** How much longer a walk through Berlin's streets is than the straight line, roughly. */
        const val WALKING_FACTOR = 1.3

        private const val EARTH_RADIUS_METRES = 6_371_000
        private const val METRES_PER_DEGREE = 111_320.0
        private const val ROUND_TO = 10

        fun walkingMetres(straightMetres: Double): Int = (straightMetres * WALKING_FACTOR / ROUND_TO).roundToInt() * ROUND_TO
    }
}
