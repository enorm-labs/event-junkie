package de.norm.events.scraper.radar

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

/** Unit tests for [parseRadarEvents], on radar group responses saved on 2026-09-30. */
class RadarEventsTest {
    private fun fixture(name: String): String =
        javaClass.classLoader
            .getResourceAsStream("scraper/radar/radar-group-$name.json")!!
            .bufferedReader()
            .readText()

    private val koepi by lazy { parseRadarEvents(fixture("koepi"), "KØPI") }

    @Test
    fun `keeps concert and party rows and drops film, bar and meeting rows`() {
        // 26 rows: 13 film nights and 13 concert rows, 4 of which repeat another row.
        koepi.rowsRead shouldBe 26
        koepi.count shouldBe 26
        koepi.events shouldHaveSize 9
        koepi.events.map { it.title }.none { it.startsWith("Filmabend") } shouldBe true
    }

    @Test
    fun `keeps one row per start and title, the lowest node id`() {
        // Nodes 597675, 597676 and 597687 are one concert posted three times.
        val bash = koepi.events.filter { it.start.toLocalDate().toString() == "2026-10-01" }
        bash.map { it.nodeId } shouldBe listOf(597675L)
    }

    @Test
    fun `maps a row's fields`() {
        val languid = koepi.events.single { it.nodeId == 577799L }
        languid.title shouldBe "Konzert im K (A)"
        languid.start.toLocalDateTime() shouldBe LocalDateTime.of(2026, 10, 20, 20, 0)
        languid.url shouldBe "https://radar.squat.net/en/node/577799"
        languid.description!! shouldStartWith "\"Languid\""
        languid.eventType shouldBe "CONCERT"
    }

    @Test
    fun `reads no end where radar repeats the start as the end`() {
        koepi.events
            .single { it.nodeId == 577799L }
            .end
            .shouldBeNull()
    }

    @Test
    fun `keeps a free-text price as written`() {
        val abstand = parseRadarEvents(fixture("abstand"), "Abstand")
        abstand.events.single { it.title == "Geburtstagsgeballer" }.price shouldBe "7-78€"
    }

    @Test
    fun `returns an empty listing for an unparseable body`() {
        val listing = parseRadarEvents("<html>Making sure you're not a bot!</html>", "KØPI")
        listing.events.shouldBeEmpty()
        listing.rowsRead shouldBe 0
    }
}
