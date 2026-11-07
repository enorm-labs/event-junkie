package de.norm.events.transit

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.math.BigDecimal
import java.nio.file.Path

class GtfsStopReaderTest {
    @TempDir
    lateinit var dir: Path

    private fun read(files: Map<String, String> = GtfsFixture.FILES) = GtfsStopReader.read(GtfsFixture.write(dir, files))

    @Test
    fun `reads the stations of U-Bahn, S-Bahn and tram lines, at the parent station, and leaves out a bus-only stop`() {
        read() shouldContainExactly
            listOf(
                TransitStation("orphan-platform", "U \"Schlesisches\" Tor", BigDecimal("52.501147"), BigDecimal("13.441821"), listOf("U1")),
                TransitStation("potsdam-hbf", "Potsdam, Hauptbahnhof", BigDecimal("52.391659"), BigDecimal("13.066798"), listOf("M10")),
                TransitStation("warschauer", "S+U Warschauer Str.", BigDecimal("52.505289"), BigDecimal("13.449250"), listOf("S3", "S5", "S25", "U1"))
            )
    }

    @Test
    fun `fails on a feed without one of the four files`() {
        shouldThrow<IllegalArgumentException> { read(GtfsFixture.FILES - "trips.txt") }.message shouldContain "trips.txt"
    }

    @Test
    fun `reads the columns by the header, in any order`() {
        val routes =
            """
            route_type,route_short_name,route_id
            0,12,r-12
            """.trimIndent()
        val trips = "trip_id,route_id\nt-12,r-12"
        val stopTimes = "stop_id,trip_id\nmitte,t-12"
        val stops = "stop_lon,stop_lat,stop_name,stop_id\n13.4,52.52,Am Kupfergraben (Berlin),mitte"

        read(mapOf("routes.txt" to routes, "trips.txt" to trips, "stop_times.txt" to stopTimes, "stops.txt" to stops)) shouldContainExactly
            listOf(TransitStation("mitte", "Am Kupfergraben", BigDecimal("52.520000"), BigDecimal("13.400000"), listOf("12")))
    }

    @Test
    fun `parses quoted fields with commas and doubled quotes`() {
        GtfsStopReader.parseCsvLine("\"a, b\",\"say \"\"hi\"\"\",,c") shouldBe listOf("a, b", "say \"hi\"", "", "c")
    }

    @Test
    fun `classifies route types into modes`() {
        LineMode.of(109, "S1") shouldBe LineMode.SUBURBAN
        LineMode.of(2, "S9") shouldBe LineMode.SUBURBAN
        LineMode.of(2, "RE1") shouldBe null
        LineMode.of(400, "U8") shouldBe LineMode.SUBWAY
        LineMode.of(1, "U2") shouldBe LineMode.SUBWAY
        LineMode.of(900, "M4") shouldBe LineMode.TRAM
        LineMode.of(0, "21") shouldBe LineMode.TRAM
        LineMode.of(700, "M41") shouldBe null
        LineMode.of(1000, "F10") shouldBe null
    }
}
