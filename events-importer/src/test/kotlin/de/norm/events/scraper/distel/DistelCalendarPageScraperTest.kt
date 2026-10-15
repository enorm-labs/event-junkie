package de.norm.events.scraper.distel

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.time.LocalDate
import java.time.LocalTime

class DistelCalendarPageScraperTest {
    private val scraper = DistelCalendarPageScraper()
    private val url = "https://distel-berlin.de/spielplan/kalender/"

    private fun document(name: String) =
        Jsoup.parse(
            javaClass.classLoader
                .getResourceAsStream("scraper/distel/$name")!!
                .bufferedReader()
                .readText(),
            url
        )

    @Test
    fun `reads every performance of the month`() {
        scraper.scrape(document("distel-kalender.html"), url) shouldHaveSize 32
    }

    @Test
    fun `reads a performance with its shop id as identity`() {
        val first = scraper.scrape(document("distel-kalender.html"), url).first()

        first.title shouldBe "Die Rückkehr der Späti-Ritter"
        first.subtitle shouldBe "Kiosk. Krise. Kanzlerschaft."
        first.eventType shouldBe "COMEDY"
        first.eventDate shouldBe LocalDate.of(2026, 10, 3)
        first.startTime shouldBe LocalTime.of(15, 0)
        first.sourceUrl shouldBe "https://distel-berlin.de/spielplan/event/die-rueckkehr-der-spaeti-ritter/"
        first.sourceId shouldBe "distel:die-rueckkehr-der-spaeti-ritter-2026-10-03-1500"
        first.ticketUrl shouldBe "https://shop.distel-berlin.de/webshop/webticket/shop?event=12210"
        first.soldOut shouldBe false
    }

    @Test
    fun `marks a performance with no seat left as sold out`() {
        scraper.scrape(document("distel-kalender.html"), url).count { it.soldOut } shouldBe 1
    }

    @Test
    fun `reads a later month and stops at an empty one`() {
        val february = scraper.scrape(document("distel-kalender-202702.html"), url)

        february shouldHaveSize 2
        february.first().ticketUrl shouldBe null
        february.all { it.eventDate.year == 2027 && it.eventDate.monthValue == 2 } shouldBe true
        scraper.isEmptyMonth(document("distel-kalender-202703-empty.html")) shouldBe true
        scraper.scrape(document("distel-kalender-202703-empty.html"), url).shouldBeEmpty()
    }

    @Test
    fun `bills the guest and host of a talk from the calendar`() {
        val talk = scraper.scrape(document("distel-kalender.html"), url).first { "Masala" in it.title }

        talk.eventType shouldBe "READING"
        talk.artists.map { it.name } shouldBe listOf("Carlo Masala", "Ulrike Herrmann")
        talk.artists.all { it.role == "HEADLINER" && it.titleDerived } shouldBe true
    }

    @Test
    fun `bills nobody on an ensemble show or one of its marked performances`() {
        val ensemble = scraper.scrape(document("distel-kalender.html"), url).filter { "Mit aller Kraft" in it.title }

        ensemble.map { it.title }.toSet() shouldBe
            setOf("Mit aller Kraft liegen lassen", "PREMIERE: Mit aller Kraft liegen lassen", "Voraufführung: Mit aller Kraft liegen lassen")
        ensemble.all { it.eventType == "COMEDY" && it.artists.isEmpty() } shouldBe true
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(
        delimiter = '|',
        value = [
            "MISSVERSTEHEN SIE MICH RICHTIG – Prof. Carlo Masala zu Gast bei Ulrike Herrmann |  | READING | Carlo Masala;Ulrike Herrmann",
            "MISSVERSTEHEN SIE MICH RICHTIG: Lena Cassel zu Gast bei Kevin Kühnert |  | READING | Lena Cassel;Kevin Kühnert",
            "MISSVERSTEHEN SIE MICH RICHTIG – Marcel Reif zu Gast bei Kevin Kühnert |  | READING | Marcel Reif;Kevin Kühnert",
            "MISSVERSTEHEN SIE MICH RICHTIG – Dr. Herbert Diess zu Gast bei Maja Göpel | Industrie neu denken | READING | Herbert Diess;Maja Göpel",
            "Wenzel: Lieder & Texte | Solo-Konzert | CONCERT | Wenzel",
            "Walter Sittler & Johann von Bülow: Ich gebe Ihnen mein Ehrenwort! | Die Weltgeschichte der Lüge | COMEDY | Walter Sittler;Johann von Bülow",
            "Manfred Lütz: Wie Sie unvermeidlich glücklich werden | Ein kabarettistischer Vortragsabend | COMEDY | Manfred Lütz",
            "Michael Krebs: OPTIMISMUS – jetzt stark reduziert | Soloprogramm | COMEDY | Michael Krebs",
            "LaLeLu: Urlaub vom Hirn | A-cappella-Comedy für die überlasteten Synapsen | COMEDY | LaLeLu",
            "Ginzburg Dynastie: Jiddisch Swing & Klezmer | Konzert | CONCERT | Ginzburg Dynastie",
            "Ilko-Sascha Kowalczuk und Bodo Ramelow: Die neue Mauer | Ein Gespräch über den Osten | COMEDY | Ilko-Sascha Kowalczuk;Bodo Ramelow",
            "Antje Rietz & Band: Eine Trompete kommt selten allein | Swing in der Friedrichstraße | COMEDY | Antje Rietz & Band",
            "Max Goldt liest: Aber? | Ein literarisches Comeback | READING | Max Goldt",
            "Comedian Harmonists, Max Raabe, Prof. Dr. Harald Lesch: Ein langer Abend |  | COMEDY | Comedian Harmonists;Max Raabe;Harald Lesch",
            "Die Rückkehr der Späti-Ritter | Kiosk. Krise. Kanzlerschaft. | COMEDY |",
            "Generalprobe: Gut im Abgang | Der DISTEL-Jahresrückblick 2026 | COMEDY |",
            "PREMIERE: Gut im Abgang | Der DISTEL-Jahresrückblick 2026 | COMEDY |",
            "Dernière: Gut im Abgang | Der DISTEL-Jahresrückblick 2026 | COMEDY |",
            "Matinee: Mit aller Kraft liegen lassen | Dauerbaustelle Deutschland | COMEDY |",
            "Bunter Weihnachtsteller: Die Rückkehr der Späti-Ritter | Kiosk. Krise. Kanzlerschaft. | COMEDY |",
            "FIL – Lieder of the world | Lieder of the world | COMEDY |"
        ]
    )
    fun `types and bills a calendar row from its title and subtitle`(
        title: String,
        subtitle: String?,
        type: String,
        acts: String?
    ) {
        distelEventType(title, subtitle) shouldBe type
        distelActs(title).map { it.name } shouldBe acts?.split(';').orEmpty()
    }
}
