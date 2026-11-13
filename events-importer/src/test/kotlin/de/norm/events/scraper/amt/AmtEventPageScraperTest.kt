package de.norm.events.scraper.amt

import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.fixtureDocument
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.time.LocalDate
import java.time.LocalTime

class AmtEventPageScraperTest {
    private val night =
        ScrapedEvent(
            title = "Loone x Anja Schneider, Milena Adamis, Anna Amato & Steven Shade",
            eventDate = LocalDate.of(2026, 11, 18),
            sourceUrl = "https://www.club-amt.berlin/event/loone-invites",
            sourceId = "amt:loone-invites"
        )

    private fun enrich(fixture: String) = addAmtEventPageFields(night, fixtureDocument("amt/$fixture", night.sourceUrl))

    private fun enrichProse(vararg paragraphs: String) =
        addAmtEventPageFields(
            night,
            Jsoup.parse("""<div class="rich-text-block">${paragraphs.joinToString("") { "<p>$it</p>" }}</div>""", night.sourceUrl)
        )

    @Test
    fun `a Start label gives the start, ahead of the bare-hour running order and the from-6pm sentence`() {
        val event = enrich("amt-event-loone-invites.html")

        event.startTime shouldBe LocalTime.of(19, 0)
        event.description.orEmpty() shouldContain "Start: 7:00 PM"
    }

    @Test
    fun `the first running-order slot gives the start when no label states one`() {
        enrich("amt-event-loone-x-arcane-xiii.html").startTime shouldBe LocalTime.of(19, 0)
    }

    @Test
    fun `an early-entry ticket window is no start`() {
        val event = enrich("amt-event-cage.html")

        event.startTime.shouldBeNull()
        event.description.orEmpty() shouldContain "arrival 22:00–23:00"
    }

    @Test
    fun `a night whose prose states no time keeps no start`() {
        enrich("amt-event-brace-brace.html").startTime.shouldBeNull()
    }

    @ParameterizedTest
    @CsvSource(
        "'Start: 12:00 AM', 00:00",
        "'Start: 12:00 PM', 12:00",
        "'Start: 11:30 pm', 23:30",
        "'Start: 23:00', 23:00",
        "'23:00–01:00 — Night Owl', 23:00",
        "'0:00-2:00 — After Midnight', 00:00",
        // 13901 and 13902: the Loone nights print a bare hour with a meridiem, one led by a zero-width joiner.
        "'7PM mchvlsm', 19:00",
        "'\u200D5PM Florian Meindl', 17:00",
        "'12AM Late Shift', 00:00",
        "'6:30 p.m. Opening Set', 18:30"
    )
    fun `the prose start reads both clocks and a slot over midnight`(
        paragraph: String,
        expected: LocalTime
    ) {
        enrichProse("Line-up", paragraph, "See you on the dancefloor.").startTime shouldBe expected
    }

    @Test
    fun `a slot inside a sentence and a bare-hour slot are no start`() {
        enrichProse("Doors close for the arrival 22:00–23:00 window.", "19 - 20 Anna Amato").startTime.shouldBeNull()
        enrichProse("Join us from 6pm for the opening.", "PM Ly Sas").startTime.shouldBeNull()
    }

    @Test
    fun `a start the month page already set is kept`() {
        val event = addAmtEventPageFields(night.copy(startTime = LocalTime.of(22, 0)), fixtureDocument("amt/amt-event-loone-invites.html", night.sourceUrl))

        event.startTime shouldBe LocalTime.of(22, 0)
    }
}
