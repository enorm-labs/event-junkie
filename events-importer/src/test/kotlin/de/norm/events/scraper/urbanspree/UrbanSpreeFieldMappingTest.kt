package de.norm.events.scraper.urbanspree

import de.norm.events.event.EventStatus
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

/** Unit tests for the title/category mapping shared by the two Urban Spree scrapers. */
class UrbanSpreeFieldMappingTest {
    // --- splitUrbanSpreeBilling ---

    @Test
    fun `splitUrbanSpreeBilling peels a piped support-billing note off the headline`() {
        val (headline, note) = splitUrbanSpreeBilling("WISBORG Phantomschmerz Tour - BERLIN | Special Guest: The Fright")

        headline shouldBe "WISBORG Phantomschmerz Tour - BERLIN"
        note shouldBe "| Special Guest: The Fright"
    }

    @Test
    fun `splitUrbanSpreeBilling accepts the other support markers and separators`() {
        splitUrbanSpreeBilling("Band X - Support: Opener Act").first shouldBe "Band X"
        splitUrbanSpreeBilling("Band X | Supports: A + B").second shouldBe "| Supports: A + B"
        splitUrbanSpreeBilling("Band X Opener: Someone").first shouldBe "Band X"
    }

    @Test
    fun `splitUrbanSpreeBilling leaves a piped venue tail to the tail stripper`() {
        // No colon after a support marker, so this is not a billing note.
        val (headline, note) = splitUrbanSpreeBilling("JUD | Urban Spree Berlin")

        headline shouldBe "JUD | Urban Spree Berlin"
        note.shouldBeNull()
    }

    @Test
    fun `splitUrbanSpreeBilling keeps a title that is nothing but a billing note`() {
        val (headline, note) = splitUrbanSpreeBilling("Special Guest: The Fright")

        headline shouldBe "Special Guest: The Fright"
        note.shouldBeNull()
    }

    // --- cleanUrbanSpreeTitle ---

    @Test
    fun `cleanUrbanSpreeTitle strips chained venue and city tails`() {
        cleanUrbanSpreeTitle("Coilguns - Berlin - Urban Spree") shouldBe "Coilguns"
        cleanUrbanSpreeTitle("TWIN NOIR + HINFORT - URBAN SPREE, BERLIN") shouldBe "TWIN NOIR + HINFORT"
        cleanUrbanSpreeTitle("JUD | Urban Spree Berlin") shouldBe "JUD"
        cleanUrbanSpreeTitle("MAYFLOWER MADAME + BLKE - Berlin Show @Urban Spree") shouldBe "MAYFLOWER MADAME + BLKE"
        cleanUrbanSpreeTitle("New Candys (IT Fuzz Club) live at Urban Spree Berlin") shouldBe "New Candys (IT Fuzz Club)"
        cleanUrbanSpreeTitle("LES SHIRLEY - BERLIN, 15.09.2026") shouldBe "LES SHIRLEY"
    }

    @Test
    fun `cleanUrbanSpreeTitle keeps an act whose own name ends in a place word`() {
        // Only a delimiter opens a tail, so the band survives — with or without a venue tail.
        cleanUrbanSpreeTitle("Isolation Berlin") shouldBe "Isolation Berlin"
        cleanUrbanSpreeTitle("Isolation Berlin | Urban Spree Berlin") shouldBe "Isolation Berlin"
    }

    @Test
    fun `cleanUrbanSpreeTitle strips the leading status marker`() {
        cleanUrbanSpreeTitle("CANCELLED - SOM - Berlin - Urban Spree") shouldBe "SOM"
    }

    @Test
    fun `cleanUrbanSpreeTitle leaves an undecorated title alone`() {
        cleanUrbanSpreeTitle("FLUXO INVITES EBONY") shouldBe "FLUXO INVITES EBONY"
        cleanUrbanSpreeTitle("THIS ETERNAL DECAY ( Dark Wave IT) + NIGHT NAIL (Dark Wave US/DE)") shouldBe
            "THIS ETERNAL DECAY ( Dark Wave IT) + NIGHT NAIL (Dark Wave US/DE)"
    }

    // --- urbanSpreeStatus ---

    @Test
    fun `a SOLD OUT lead marks the show sold out and leaves the act`() {
        // Production stored `Sold Out - Otha` as the act, on sale (#1841).
        cleanUrbanSpreeTitle("SOLD OUT - Otha") shouldBe "Otha"
        urbanSpreeSoldOut("SOLD OUT - Otha") shouldBe true
        urbanSpreeStatus("SOLD OUT - Otha") shouldBe EventStatus.SCHEDULED.name
        urbanSpreeSoldOut("CANCELLED - SOM") shouldBe false
        urbanSpreeSoldOut("Otha") shouldBe false
    }

    @Test
    fun `urbanSpreeStatus reads the leading status marker`() {
        urbanSpreeStatus("CANCELLED - SOM - Berlin - Urban Spree") shouldBe EventStatus.CANCELLED.name
        urbanSpreeStatus("ABGESAGT - Band") shouldBe EventStatus.CANCELLED.name
        urbanSpreeStatus("MÚR - Berlin") shouldBe EventStatus.SCHEDULED.name
    }

    @Test
    fun `urbanSpreeStatus ignores a status word that is not a leading marker`() {
        urbanSpreeStatus("The Cancelled Plans") shouldBe EventStatus.SCHEDULED.name
    }

    // --- normalizeAssetUrl ---

    @Test
    fun `normalizeAssetUrl percent-encodes spaces in a media filename`() {
        normalizeAssetUrl("https://www.urbanspree.com/assets/FLUXO invites EBONY.jpeg") shouldBe
            "https://www.urbanspree.com/assets/FLUXO%20invites%20EBONY.jpeg"
        normalizeAssetUrl("https://www.urbanspree.com/assets/plain.jpg") shouldBe "https://www.urbanspree.com/assets/plain.jpg"
        normalizeAssetUrl(null).shouldBeNull()
        normalizeAssetUrl("  ").shouldBeNull()
    }

    // --- urbanSpreeHeaderStart and urbanSpreeDjSets (#1904) ---

    private val klubnacht =
        "URBAN SPREE KLUBNACHT 25.09.26 21:00 — LATE One night, two spaces. The night starts outside in the Urban Spree " +
            "Garden at 21:00 with Albert Kraft, before moving inside to the Klub from 00:00 with Key Clef and Daraio. " +
            "GARDEN — 21:00 → 00:30 Albert Kraft — DJ set KLUB — FROM 00:00 Key Clef — DJ set Daraio — DJ set " +
            "ALBERT KRAFT Berlin-based DJ and producer"

    @Test
    fun `urbanSpreeHeaderStart reads the opening stamp for the night's own date`() {
        urbanSpreeHeaderStart(klubnacht, LocalDate.of(2026, 9, 25)) shouldBe LocalTime.of(21, 0)
    }

    @Test
    fun `urbanSpreeHeaderStart ignores a stamp for another date`() {
        urbanSpreeHeaderStart(klubnacht, LocalDate.of(2026, 9, 26)).shouldBeNull()
    }

    @Test
    fun `urbanSpreeHeaderStart finds nothing in prose without a stamp`() {
        urbanSpreeHeaderStart("Doors open at 23:59 — come early!", LocalDate.of(2026, 10, 24)).shouldBeNull()
        urbanSpreeHeaderStart(null, LocalDate.of(2026, 10, 24)).shouldBeNull()
    }

    // --- urbanSpreeDoorsAndStart (#2787) ---

    private val seven = LocalTime.of(19, 0)
    private val eight = LocalTime.of(20, 0)

    @Test
    fun `urbanSpreeDoorsAndStart reads a clock-first pair whose doors match the hero`() {
        urbanSpreeDoorsAndStart("CLUB 20 TOUR 2026 19.00 Doors 20:00 Start Norwegian rave-pop artist Otha", seven) shouldBe (seven to eight)
    }

    @Test
    fun `urbanSpreeDoorsAndStart reads a label-first pair in English and German`() {
        urbanSpreeDoorsAndStart("Banda Entopica 28.11.2026 - Urban Spree Doors: 20:00 | Start: 21:00 Tickets: soon", eight) shouldBe
            (eight to LocalTime.of(21, 0))
        urbanSpreeDoorsAndStart("Coilguns Einlass: 19:00 Beginn: 20:00", seven) shouldBe (seven to eight)
        urbanSpreeDoorsAndStart("NO CIGAR ┇ 7. November 2026 ┇ Urban Spree ┋ Einlass 19h ┋ Beginn 20h", seven) shouldBe (seven to eight)
        val tribute =
            "Tribute To Nothing 20 yrs. of „How Many Times Did We Live“ Special Guest: Leaves 23.10.2026, Berlin, Urban Spree " +
                "Einlass: 19:00 Beginn: 20:00"
        urbanSpreeDoorsAndStart(tribute, seven) shouldBe (seven to eight)
        urbanSpreeDoorsAndStart("Unpeople Einlass: 19:30 Uhr | Beginn: 20:30 Uhr", LocalTime.of(19, 30)) shouldBe
            (LocalTime.of(19, 30) to LocalTime.of(20, 30))
    }

    @Test
    fun `urbanSpreeDoorsAndStart ignores a pair whose doors differ from the hero`() {
        urbanSpreeDoorsAndStart("Doors: 20:00 | Start: 21:00", seven).shouldBeNull()
        urbanSpreeDoorsAndStart("19.00 Doors 20:00 Start", null).shouldBeNull()
    }

    @Test
    fun `urbanSpreeDoorsAndStart ignores a start that is not after the doors`() {
        urbanSpreeDoorsAndStart("Doors: 23:00 | Start: 00:30", LocalTime.of(23, 0)).shouldBeNull()
        urbanSpreeDoorsAndStart("Doors: 20:00 | Start: 20:00", eight).shouldBeNull()
    }

    @Test
    fun `urbanSpreeDoorsAndStart ignores a doors line without a start`() {
        urbanSpreeDoorsAndStart("Doors: 20H", eight).shouldBeNull()
        urbanSpreeDoorsAndStart("Einlass 19h", seven).shouldBeNull()
        urbanSpreeDoorsAndStart(null, seven).shouldBeNull()
    }

    @Test
    fun `urbanSpreeDoorsAndStart looks only at the description's opening`() {
        val blurb =
            "A long biography of the band that runs on and on about tours and records and labels and festivals. " +
                "It goes on about the albums, the members, the hometown and the van that broke down on the motorway. " +
                "Their last show here: Doors: 20:00 | Start: 21:00"
        urbanSpreeDoorsAndStart(blurb, eight).shouldBeNull()
    }

    @Test
    fun `urbanSpreeDjSets reads each running-order entry once, in order`() {
        urbanSpreeDjSets(klubnacht) shouldContainExactly listOf("Albert Kraft", "Key Clef", "Daraio")
    }

    @Test
    fun `urbanSpreeDjSets finds nothing in prose that only mentions a DJ set`() {
        urbanSpreeDjSets("The night closes with a DJ set by the band's drummer.").shouldBeEmpty()
    }

    // --- urbanSpreeFeaturedActs (#2350) ---

    @Test
    fun `urbanSpreeFeaturedActs reads a Featuring sentence's list up to the clause after it`() {
        urbanSpreeFeaturedActs(
            "Human Tree takes over Urban Spree for a special Klubnacht. Featuring Bam Bam’s Boogie, Delta Division and N Ska, " +
                "the programme unfolds as a continuous musical journey."
        ) shouldContainExactly listOf("Bam Bam’s Boogie", "Delta Division", "N Ska")
    }

    @Test
    fun `urbanSpreeFeaturedActs reads a list that ends the sentence`() {
        urbanSpreeFeaturedActs("Featuring Architect and Daniel Myer.") shouldContainExactly listOf("Architect", "Daniel Myer")
    }

    @Test
    fun `urbanSpreeFeaturedActs ignores featuring in the middle of a sentence`() {
        // Alice Gift's biography on the Flame Parade page.
        urbanSpreeFeaturedActs("He played on a compilation featuring artists such as The KVB and Twin Tribes.").shouldBeEmpty()
    }

    @Test
    fun `urbanSpreeFeaturedActs rejects a list with an item that is prose`() {
        urbanSpreeFeaturedActs("Featuring 2 Berlin-based cutting-edge bands, exploring between funk and electro.").shouldBeEmpty()
        urbanSpreeFeaturedActs("Featuring Architect and a rare live set by someone special.").shouldBeEmpty()
        urbanSpreeFeaturedActs(null).shouldBeEmpty()
    }
}
