package de.norm.events.scraper.morphine

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime

/**
 * Unit tests for the field rules the two Morphine Raum scrapers share.
 *
 * Every string asserted here is a real one from the venue's programme, so the cases pin the
 * boundaries the mapping claims: which billing tail is stripped, which free-text line reads as
 * pricing, and which price is too ambiguous to store.
 */
class MorphineFieldMappingTest {
    // --- stripLiveRecordingSuffix ---

    @Test
    fun `strips the Live Recording billing tail from a derived artist name`() {
        stripLiveRecordingSuffix("Invisible Weather - Live Recording") shouldBe "Invisible Weather"
    }

    @Test
    fun `accepts every dash spelling the venue uses, and a bare space`() {
        stripLiveRecordingSuffix("Raphael Rogiński – Qırım - Live Recording") shouldBe "Raphael Rogiński – Qırım"
        stripLiveRecordingSuffix("Invisible Weather — Live Recording") shouldBe "Invisible Weather"
        stripLiveRecordingSuffix("Invisible Weather Live Recording") shouldBe "Invisible Weather"
    }

    @Test
    fun `leaves a name that carries no billing tail unchanged`() {
        stripLiveRecordingSuffix("VINYL REDUCTION") shouldBe "VINYL REDUCTION"
    }

    @Test
    fun `matches the tail only at the end, so an act named for it survives`() {
        stripLiveRecordingSuffix("Live Recording Ensemble") shouldBe "Live Recording Ensemble"
    }

    @Test
    fun `keeps the input when stripping would leave nothing`() {
        stripLiveRecordingSuffix("Live Recording") shouldBe "Live Recording"
    }

    // --- parseDayLineDate ---

    // #1674 — the four shapes the venue writes on a set line beside a bare name.
    @Test
    fun `reads the acts off a set line that carries a frame, an instrument, a dash list or a member list`() {
        val framed = "Uncanny Valley presents: Gareth Psaltis Live - Temple Rat Live - Jacob Stoy"
        morphineSetLineActs(framed, "Gareth Psaltis - Temple Rat - Jacob Stoy") shouldBe listOf("Gareth Psaltis", "Temple Rat", "Jacob Stoy")
        morphineSetLineActs("Jakob Vasak performs with the Kobophon", "Jakob Vasak - Live Recording") shouldBe listOf("Jakob Vasak")
        morphineSetLineActs("Le Révélateur Ciné-Concert - Réka Csiszér", "Le Révélateur Ciné-Concert") shouldBe listOf("Réka Csiszér")
        morphineSetLineActs("PICI - Clémence Manachère & Polina Pohozha", "PICI") shouldBe listOf("PICI")
        // A plain name, a co-bill and a work title behave as before; the co-bill split is the caller's.
        morphineSetLineActs("Sardy Fardy", "Sardy Fardy - Live Recording") shouldBe listOf("Sardy Fardy")
        morphineSetLineActs("ALL ABOUT BIRDS & JON ROSE: HINTERLAND!", "All About Birds") shouldBe listOf("ALL ABOUT BIRDS & JON ROSE")
        // A pair of names stays glued: the sync-time head rule (#302) knows whether `Alister Spence` is an act.
        morphineSetLineActs("Alister Spence – Within Without", "Alister Spence – Within Without") shouldBe listOf("Alister Spence – Within Without")
    }

    @Test
    fun `reads the date out of the day header line`() {
        parseDayLineDate("Friday, 07.08.26, door  20:00") shouldBe LocalDate.of(2026, 8, 7)
    }

    @Test
    fun `returns null when the day line states no date`() {
        parseDayLineDate("Saturday, door 20:00") shouldBe null
        parseDayLineDate("") shouldBe null
        parseDayLineDate(null) shouldBe null
    }

    // --- parseDayLineDoors ---

    @Test
    fun `reads the door time out of the day header line`() {
        parseDayLineDoors("Friday, 07.08.26, door  20:00") shouldBe LocalTime.of(20, 0)
        parseDayLineDoors("doors: 21:30") shouldBe LocalTime.of(21, 30)
    }

    @Test
    fun `only the venue's own door label counts, never Einlass`() {
        parseDayLineDoors("Friday, 07.08.26, Einlass 20:00") shouldBe null
        parseDayLineDoors(null) shouldBe null
    }

    // --- readPriceNote ---

    @Test
    fun `keeps a pricing line verbatim`() {
        readPriceNote("10 - 15 Euro donation") shouldBe "10 - 15 Euro donation"
        readPriceNote("Free entry") shouldBe "Free entry"
    }

    @Test
    fun `accepts the venue's Eu abbreviation`() {
        val note = "10 - 15 Eu Sliding scale Donation at the door."
        readPriceNote(note) shouldBe note
    }

    @Test
    fun `rejects a house rule the box also carries`() {
        readPriceNote("Concert (two sets!) starts at 20:00 sharp.") shouldBe null
    }

    @Test
    fun `the Eu marker is word-anchored, so it cannot match inside a name`() {
        readPriceNote("Euphoria Ensemble live.") shouldBe null
    }

    @Test
    fun `returns null for a blank or absent box`() {
        readPriceNote("   ") shouldBe null
        readPriceNote(null) shouldBe null
    }

    // --- parseDoorPrice ---

    @Test
    fun `reads a note that states exactly one amount`() {
        parseDoorPrice("10 Euro At The Door") shouldBe BigDecimal("10")
        parseDoorPrice("12,50 Euro at the door") shouldBe BigDecimal("12.50")
    }

    @Test
    fun `a repeated amount is still one amount`() {
        parseDoorPrice("10 Euro donation, 10 Euro at the door") shouldBe BigDecimal("10")
    }

    @Test
    fun `a range has no single price, so none is stored`() {
        parseDoorPrice("10 - 15 Euro donation") shouldBe null
        parseDoorPrice("€10-15 on the door") shouldBe null
        parseDoorPrice("Sliding scale 20- 25 Euro at The Door") shouldBe null
    }

    @Test
    fun `returns null for a blank or absent note`() {
        parseDoorPrice("  ") shouldBe null
        parseDoorPrice(null) shouldBe null
    }
}
