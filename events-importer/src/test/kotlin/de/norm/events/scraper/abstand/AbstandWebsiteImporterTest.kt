package de.norm.events.scraper.abstand

import de.norm.events.event.ArtistRole
import de.norm.events.scraper.ApiClient
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ImportResult
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/** Unit tests for [AbstandWebsiteImporter], with a mocked [ApiClient]. */
class AbstandWebsiteImporterTest {
    private val apiClient: ApiClient = mockk()
    private val importer = AbstandWebsiteImporter(apiClient)

    @Test
    fun `imports the concerts, bills the bracketed bands and keeps the price text`() =
        runTest {
            coEvery { apiClient.fetchJson(any()) } returns
                javaClass.classLoader
                    .getResourceAsStream("scraper/radar/radar-group-abstand.json")!!
                    .bufferedReader()
                    .readText()

            val result = importer.importEvents("https://radar.squat.net/api/1.2/search/events.json?facets%5Bgroup%5D%5B%5D=1608")

            result.shouldBeInstanceOf<ImportResult.Success>()
            // Event 5034 is the third row: its bare `Konzert` title becomes the bill.
            result.events.map { it.title } shouldBe
                listOf("Geburtstagsgeballer", "HAMBURG. BERLIN. DRESDEN. KRACH", "Accion Mutante + Necromorph + Iron Kindl Pest")
            result.events.map { event -> event.artists.map { it.name to it.role } } shouldBe
                listOf(
                    listOf("Electric Masochists" to ArtistRole.HEADLINER, "Horrivic Visions" to ArtistRole.SUPPORT),
                    listOf("FiltzgleitA" to ArtistRole.HEADLINER, "Pogarda" to ArtistRole.SUPPORT, "Face Up To It" to ArtistRole.SUPPORT),
                    listOf("Accion Mutante" to ArtistRole.HEADLINER, "Necromorph" to ArtistRole.SUPPORT, "Iron Kindl Pest" to ArtistRole.SUPPORT)
                )
            result.events.first().priceNote shouldBe "7-78€"
            importer.eventSource shouldBe EventSource.ABSTAND
        }

    @Test
    fun `bills the DJs a mit DJs list names, and no one from prose without brackets`() {
        val halloween =
            parseAbstandBill(
                "!!!Halloween Party im Abstand!!!\nKonzert mit Diapers (Synthpunk/Schweinemetal) und Rosa hat Spliss (Neo Post-Punk)\n" +
                    "Anschließend Hits vom Plattenteller mit DJs Moppi Galoppi, Dirty Jensing und AnnaConda. Tanzen wir die Nacht durch!"
            )
        halloween.artists.map { it.name to it.role } shouldBe
            listOf(
                "Diapers" to ArtistRole.HEADLINER,
                "Rosa hat Spliss" to ArtistRole.SUPPORT,
                "Moppi Galoppi" to ArtistRole.DJ,
                "Dirty Jensing" to ArtistRole.DJ,
                "AnnaConda" to ArtistRole.DJ
            )
        parseAbstandBill("Konzert mit tollen Bands aus Berlin").artists.shouldBeEmpty()
    }
}
