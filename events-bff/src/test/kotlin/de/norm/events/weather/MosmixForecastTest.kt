package de.norm.events.weather

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.maps.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class MosmixForecastTest {
    private fun fixture(name: String) = requireNotNull(javaClass.getResourceAsStream("/weather/$name")) { "missing fixture $name" }

    @Test
    fun `reads a MOSMIX_L file as the DWD publishes it`() {
        val forecast = MosmixForecast.fromKmz(fixture("MOSMIX_L_2026110709_10384.kmz"))

        forecast.issuedAt shouldBe Instant.parse("2026-11-07T09:00:00Z")
        forecast.hours shouldHaveSize 247
        val first = forecast.hours[Instant.parse("2026-11-07T10:00:00Z")].shouldNotBeNull()
        first.temperatureCelsius.shouldNotBeNull() shouldBe (3.4 plusOrMinus 0.001)
        first.significantWeather shouldBe 3
        first.rainProbabilityPercent shouldBe 1
    }

    @Test
    fun `a dash is a value MOSMIX does not forecast`() {
        val forecast = MosmixForecast.fromKmz(fixture("MOSMIX_L_LATEST_10384.kmz"))

        forecast.hours[Instant.parse("2027-06-12T23:00:00Z")]
            .shouldNotBeNull()
            .rainProbabilityPercent
            .shouldBeNull()
        forecast.hours[Instant.parse("2027-06-13T00:00:00Z")]
            .shouldNotBeNull()
            .significantWeather
            .shouldBeNull()
    }

    @Test
    fun `an archive without a KML file is refused`() {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            zip.putNextEntry(ZipEntry("readme.txt"))
            zip.write("not a forecast".toByteArray())
            zip.closeEntry()
        }

        shouldThrow<IllegalArgumentException> { MosmixForecast.fromKmz(ByteArrayInputStream(bytes.toByteArray())) }
    }

    @Test
    fun `a KML with a DTD is refused, so no entity is resolved`() {
        val kml =
            """<?xml version="1.0"?><!DOCTYPE kml [<!ENTITY x SYSTEM "file:///etc/passwd">]><kml><IssueTime>&x;</IssueTime></kml>"""

        shouldThrow<Exception> { MosmixForecast.fromKml(ByteArrayInputStream(kml.toByteArray())) }
    }
}
