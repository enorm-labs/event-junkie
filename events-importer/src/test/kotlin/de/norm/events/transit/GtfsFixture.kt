package de.norm.events.transit

import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * A hand-made GTFS feed in VBB's shape: quoted fields, extended route types, platforms under a parent station.
 * Warschauer Str. has S-Bahn, U-Bahn and a bus; Potsdam Hbf a tram and a regional train; one stop has only a bus.
 */
object GtfsFixture {
    val FILES: Map<String, String> =
        mapOf(
            "routes.txt" to
                """
                ${Char(0xFEFF)}"route_id","agency_id","route_short_name","route_long_name","route_type"
                "r-s5","1","S5","",109
                "r-s25","1","S25","",109
                "r-s3","1","S3","",2
                "r-u1","1","U1","",400
                "r-m10","1","M10","",900
                "r-bus","1","347","",700
                "r-re1","1","RE1","",100
                """.trimIndent(),
            "trips.txt" to
                """
                "route_id","service_id","trip_id","trip_headsign"
                "r-s5","1","t-s5","Strausberg Nord"
                "r-s25","1","t-s25","Teltow Stadt"
                "r-s3","1","t-s3","Erkner"
                "r-u1","1","t-u1","Uhlandstr."
                "r-m10","1","t-m10","Hauptbahnhof"
                "r-bus","1","t-bus","Tierpark"
                "r-re1","1","t-re1","Magdeburg"
                """.trimIndent(),
            "stop_times.txt" to
                """
                "trip_id","arrival_time","departure_time","stop_id","stop_sequence"
                "t-s5","08:00:00","08:00:00","warschauer::1",1
                "t-s25","08:00:00","08:00:00","warschauer::1",1
                "t-s3","08:00:00","08:00:00","warschauer::2",1
                "t-u1","08:01:00","08:01:00","warschauer::3",2
                "t-bus","08:02:00","08:02:00","warschauer::4",3
                "t-s5","08:05:00","08:05:00","warschauer::1",2
                "t-m10","09:00:00","09:00:00","potsdam-hbf::1",1
                "t-re1","09:00:00","09:00:00","potsdam-hbf::2",1
                "t-bus","09:30:00","09:30:00","bus-only",4
                "t-u1","09:40:00","09:40:00","orphan-platform",5
                "t-unknown","09:50:00","09:50:00","warschauer::1",6
                "t-u1","short"
                """.trimIndent(),
            "stops.txt" to
                """
                "stop_id","stop_code","stop_name","stop_desc","stop_lat","stop_lon","location_type","parent_station"
                "warschauer","","S+U Warschauer Str. (Berlin)","","52.5052890","13.4492500","1",""
                "warschauer::1","","S+U Warschauer Str. (Berlin)","","52.5051","13.4490","0","warschauer"
                "warschauer::2","","S+U Warschauer Str. (Berlin)","","52.5052","13.4491","0","warschauer"
                "warschauer::3","","S+U Warschauer Str. (Berlin)","","52.5054","13.4497","0","warschauer"
                "warschauer::4","","S+U Warschauer Str. (Berlin)","","52.5050","13.4500","0","warschauer"
                "potsdam-hbf","","Potsdam, Hauptbahnhof","","52.391659","13.066798","1",""
                "potsdam-hbf::1","","Potsdam, Hauptbahnhof","","52.3917","13.0668","0","potsdam-hbf"
                "potsdam-hbf::2","","Potsdam, Hauptbahnhof","","52.3917","13.0668","0","potsdam-hbf"
                "bus-only","","Revaler Str. (Berlin)","","52.5070","13.4540","0",""
                "orphan-platform","","U ""Schlesisches"" Tor (Berlin)","","52.501147","13.441821","0","missing-parent"
                """.trimIndent()
        )

    fun bytes(files: Map<String, String> = FILES): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            files.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    fun write(
        dir: Path,
        files: Map<String, String> = FILES
    ): Path = Files.write(dir.resolve("gtfs.zip"), bytes(files))
}
