package de.norm.events.scraper

import de.norm.events.event.ArtistRole
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate

// The titles are the venues' own, read on production on 2026-11-15 (#5080).
class TitleCrewsTest {
    private val tresor = setOf("Tresor")

    @Test
    fun `a hosted-by title names its host`() {
        hostingCrewsFromTitle("Tresor New Faces hosted by Grab The Groove", tresor) shouldBe listOf("Grab The Groove")
        hostingCrewsFromTitle("Tresor New Faces hosted by Scheißmukke", tresor) shouldBe listOf("Scheißmukke")
        hostingCrewsFromTitle("Tresor New Faces hosted by Tresor", tresor).shouldBeEmpty()
    }

    @Test
    fun `a presents title names the crew before the verb, and only that`() {
        hostingCrewsFromTitle("MissVergnügen presents ALIEN GHOST - live!", setOf("Eschschloraque")) shouldBe listOf("MissVergnügen")
        hostingCrewsFromTitle("TEMPO GALLERY presents: Olsvanger, KINTEL, Ian Zunich, and Hyperbole", emptySet()) shouldBe listOf("TEMPO GALLERY")
        hostingCrewsFromTitle("Heartbreak Pres. Night Two", emptySet()) shouldBe listOf("Heartbreak")
        hostingCrewsFromTitle("Migrant Birds present: EL BÚHO – ‘Hogar’ New Album Release Party", emptySet()) shouldBe listOf("Migrant Birds")
    }

    @Test
    fun `an invites title names its host, unless the host is the venue`() {
        hostingCrewsFromTitle("VINTENOVE BAILES invites State OFFF + Tekhole •.¸", setOf("Panke")) shouldBe listOf("VINTENOVE BAILES")
        hostingCrewsFromTitle("Tresor invites Surgeon", tresor).shouldBeEmpty()
        hostingCrewsFromTitle("Tresor invites Floorplan x FJAAK", tresor).shouldBeEmpty()
    }

    @Test
    fun `a meets title names both crews, unless the venue meets an act`() {
        hostingCrewsFromTitle("Symbiotikka meets Salty Pleasure", setOf("KitKatClub")) shouldBe listOf("Symbiotikka", "Salty Pleasure")
        hostingCrewsFromTitle("Tresor meets DJ Stingray 313", tresor).shouldBeEmpty()
    }

    @Test
    fun `a title with no marker names no crew`() {
        hostingCrewsFromTitle("Singularity", tresor).shouldBeEmpty()
        hostingCrewsFromTitle("KHOLEKTIV Showcase with Lola Kay", emptySet()).shouldBeEmpty()
        hostingCrewsFromTitle("INFECTED x TORQUE", emptySet()).shouldBeEmpty()
    }

    @Test
    fun `a pair title names both crews, cut before the night's own words`() {
        crewPairFromTitle("INFECTED x TORQUE", setOf("VOID Club")) shouldBe listOf("INFECTED", "TORQUE")
        crewPairFromTitle("tm8.club x Dissident ★ Mama Snake, Jan Loup, Cryptofauna + residents", setOf("Fitzroy")) shouldBe listOf("tm8.club", "Dissident")
        crewPairFromTitle("Last Resort x Radical Sounds Latin America", setOf("OHM")) shouldBe listOf("Last Resort", "Radical Sounds Latin America")
        crewPairFromTitle("Tresor x RA25", setOf("OHM", "Tresor")) shouldBe listOf("RA25")
        crewPairFromTitle("Sneaker Social Club", setOf("OHM")).shouldBeEmpty()
    }

    @Test
    fun `withTitleCrews credits the crew, drops one the line-up bills, and keeps a credit the source printed`() {
        val night = scrapedEvent("Black.Is & Moses Joses invites…", listOf("Moses Joses", "Kerosene"))
        night.withTitleCrews(setOf("Renate")).promoters shouldBe listOf("Black.Is")

        // The #339 fallback billed the host as the act, so it must not also be the promoter.
        scrapedEvent("Tresor New Faces hosted by Lowpass", listOf("Lowpass")).withTitleCrews(tresor).promoters.shouldBeEmpty()

        scrapedEvent("Bruits x Scam", emptyList())
            .copy(promoters = listOf("Bruits"))
            .withTitleCrews(setOf("Fitzroy"), crewPairFromTitle("Bruits x Scam", setOf("Fitzroy")))
            .promoters shouldBe listOf("Bruits")
    }

    private fun scrapedEvent(
        title: String,
        acts: List<String>
    ) = ScrapedEvent(
        title = title,
        eventDate = LocalDate.of(2026, 11, 20),
        sourceId = "renate:2026-11-20",
        sourceUrl = "https://renate.cc/",
        artists = acts.map { ScrapedArtist(it, ArtistRole.DJ) }
    )
}
