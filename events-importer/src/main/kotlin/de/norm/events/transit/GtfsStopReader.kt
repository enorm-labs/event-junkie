package de.norm.events.transit

import java.io.BufferedReader
import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.file.Path
import java.util.zip.ZipFile

/** One station of the feed and the U-Bahn, S-Bahn and tram lines that stop at it. */
data class TransitStation(
    val gtfsStopId: String,
    val name: String,
    val latitude: BigDecimal,
    val longitude: BigDecimal,
    /** S-Bahn first, then U-Bahn, then tram, each in natural order. Example: `["S5", "S7", "U5", "M5"]` */
    val lines: List<String>
)

/** The kinds of line a station is shown for. The order is the order of [TransitStation.lines]. */
internal enum class LineMode {
    SUBURBAN,
    SUBWAY,
    TRAM;

    companion object {
        /**
         * The mode of a GTFS `route_type`, basic or extended, or `null` for every other mode. VBB types its S-Bahn
         * 109, its U-Bahn 400 and its trams 900; the basic types are here for a feed that uses them.
         */
        fun of(
            routeType: Int,
            shortName: String
        ): LineMode? =
            when (routeType) {
                SUBURBAN_RAILWAY -> SUBURBAN
                RAIL -> if (S_BAHN_NAME.matches(shortName)) SUBURBAN else null
                SUBWAY_BASIC, in URBAN_RAILWAY -> SUBWAY
                TRAM_BASIC, in TRAM_EXTENDED -> TRAM
                else -> null
            }

        private const val TRAM_BASIC = 0
        private const val SUBWAY_BASIC = 1
        private const val RAIL = 2
        private const val SUBURBAN_RAILWAY = 109
        private val URBAN_RAILWAY = 400..404
        private val TRAM_EXTENDED = 900..906
        private val S_BAHN_NAME = Regex("S\\d+[A-Z]?")
    }
}

/**
 * Reads the stations a U-Bahn, S-Bahn or tram line serves out of a GTFS zip (#2724). It reads four files and no
 * more of each than it needs: `routes.txt` for the lines, `trips.txt` for the trips of those lines, `stop_times.txt`
 * for the stops of those trips, and `stops.txt` for where each stop is.
 *
 * A platform counts for its station (`parent_station`), so a station's lines are those of all its platforms and its
 * position is the station's own. A trailing ` (Berlin)` leaves the name, because every venue is in Berlin.
 *
 * The CSV reader takes one record per line. GTFS allows a line break inside a quoted field, and VBB's feed has none
 * in the four files; a record broken that way would be read as two short ones, and both are skipped.
 */
object GtfsStopReader {
    fun read(zip: Path): List<TransitStation> =
        ZipFile(zip.toFile()).use { file ->
            fun open(name: String): BufferedReader =
                requireNotNull(file.getEntry(name)) { "GTFS feed has no $name" }
                    .let { file.getInputStream(it).bufferedReader(Charsets.UTF_8) }

            val lines = open("routes.txt").use(::readLines)
            val tripLines = open("trips.txt").use { readTrips(it, lines) }
            val linesByStop = open("stop_times.txt").use { readStopLines(it, tripLines) }
            open("stops.txt").use { stations(it, linesByStop) }
        }

    /** `route_id` to the line it is, for the routes of a [LineMode]. */
    private fun readLines(reader: BufferedReader): Map<String, Line> {
        val result = HashMap<String, Line>()
        records(reader, "route_id", "route_short_name", "route_type") { (routeId, shortName, type) ->
            val name = shortName.trim()
            val mode = type.trim().toIntOrNull()?.let { LineMode.of(it, name) }
            if (mode != null && name.isNotEmpty()) result[routeId] = Line(name, mode)
        }
        return result
    }

    private fun readTrips(
        reader: BufferedReader,
        lines: Map<String, Line>
    ): Map<String, Line> {
        val result = HashMap<String, Line>()
        records(reader, "route_id", "trip_id") { (routeId, tripId) ->
            lines[routeId]?.let { result[tripId] = it }
        }
        return result
    }

    private fun readStopLines(
        reader: BufferedReader,
        tripLines: Map<String, Line>
    ): Map<String, MutableSet<Line>> {
        val result = HashMap<String, MutableSet<Line>>()
        records(reader, "trip_id", "stop_id") { (tripId, stopId) ->
            tripLines[tripId]?.let { result.getOrPut(stopId, ::HashSet).add(it) }
        }
        return result
    }

    private fun stations(
        reader: BufferedReader,
        linesByStop: Map<String, Set<Line>>
    ): List<TransitStation> {
        val stops = HashMap<String, Stop>()
        records(reader, "stop_id", "stop_name", "stop_lat", "stop_lon", "parent_station") { fields ->
            val latitude = fields[LAT].trim().toBigDecimalOrNull()
            val longitude = fields[LON].trim().toBigDecimalOrNull()
            if (latitude != null && longitude != null) {
                stops[fields[ID]] = Stop(fields[NAME].trim(), latitude, longitude, fields[PARENT].trim().ifEmpty { null })
            }
        }
        val linesByStation = HashMap<String, MutableSet<Line>>()
        linesByStop.forEach { (stopId, lines) ->
            val stop = stops[stopId] ?: return@forEach
            val stationId = stop.parent?.takeIf { it in stops } ?: stopId
            linesByStation.getOrPut(stationId, ::HashSet).addAll(lines)
        }
        return linesByStation
            .mapNotNull { (stationId, lines) ->
                val station = stops.getValue(stationId)
                val name = station.name.removeSuffix(CITY_SUFFIX).trim()
                if (name.isEmpty()) return@mapNotNull null
                TransitStation(
                    gtfsStopId = stationId,
                    name = name,
                    latitude = station.latitude.setScale(COORDINATE_SCALE, RoundingMode.HALF_UP),
                    longitude = station.longitude.setScale(COORDINATE_SCALE, RoundingMode.HALF_UP),
                    lines = lines.sortedWith(LINE_ORDER).map { it.name }.distinct()
                )
            }.sortedBy { it.gtfsStopId }
    }

    /**
     * Calls [block] with the named [columns] of every record after the header, in the order named. A column the
     * header lacks reads as empty, and a record shorter than the header is skipped.
     */
    private fun records(
        reader: BufferedReader,
        vararg columns: String,
        block: (List<String>) -> Unit
    ) {
        val header = reader.readLine()?.removePrefix(BOM)?.let(::parseCsvLine) ?: return
        val indexes = columns.map { header.indexOf(it) }
        reader.lineSequence().forEach { line ->
            if (line.isBlank()) return@forEach
            val fields = parseCsvLine(line)
            if (fields.size < header.size) return@forEach
            block(indexes.map { if (it < 0) "" else fields[it] })
        }
    }

    /** One CSV record: fields split at commas outside quotes, `""` inside quotes read as one quote. */
    internal fun parseCsvLine(line: String): List<String> {
        val fields = ArrayList<String>()
        val field = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                quoted && c == '"' && line.getOrNull(i + 1) == '"' -> {
                    field.append('"')
                    i++
                }

                c == '"' -> {
                    quoted = !quoted
                }

                c == ',' && !quoted -> {
                    fields.add(field.toString())
                    field.setLength(0)
                }

                else -> {
                    field.append(c)
                }
            }
            i++
        }
        fields.add(field.toString())
        return fields
    }

    private data class Line(
        val name: String,
        val mode: LineMode
    )

    private data class Stop(
        val name: String,
        val latitude: BigDecimal,
        val longitude: BigDecimal,
        val parent: String?
    )

    /** U+FEFF, which some exporters write before the header. Built from its code, because an editor strips it from a literal. */
    private val BOM = Char(BYTE_ORDER_MARK).toString()
    private const val BYTE_ORDER_MARK = 0xFEFF
    private const val ID = 0
    private const val NAME = 1
    private const val LAT = 2
    private const val LON = 3
    private const val PARENT = 4
    private const val CITY_SUFFIX = " (Berlin)"
    private const val COORDINATE_SCALE = 6
    private val LINE_NUMBER = Regex("^(\\D*)(\\d*)(.*)$")

    /** By mode, then natural order within it: `S2` before `S25` before `S3`, `M4` before `M10`. */
    private val LINE_ORDER: Comparator<Line> =
        compareBy<Line> { it.mode }
            .thenBy {
                LINE_NUMBER
                    .find(it.name)
                    ?.groupValues
                    ?.get(1)
                    .orEmpty()
            }.thenBy {
                LINE_NUMBER
                    .find(it.name)
                    ?.groupValues
                    ?.get(2)
                    ?.toIntOrNull() ?: Int.MAX_VALUE
            }.thenBy { it.name }
}
