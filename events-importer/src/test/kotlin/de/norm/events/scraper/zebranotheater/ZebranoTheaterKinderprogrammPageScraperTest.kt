package de.norm.events.scraper.zebranotheater

import de.norm.events.common.BERLIN
import de.norm.events.event.EventType
import de.norm.events.scraper.forChildrenPhrase
import io.kotest.matchers.shouldBe
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/** The public performances of `kinderprogramm.html`, captured on 2026-10-24, each marked `for-children` (#3796). */
class ZebranoTheaterKinderprogrammPageScraperTest {
    private val url = "https://www.zebrano-theater.de/kinderprogramm.html"
    private val clock = Clock.fixed(Instant.parse("2026-10-24T10:00:00Z"), BERLIN)
    private val events =
        ZebranoTheaterKinderprogrammPageScraper(clock).scrape(
            Jsoup.parse(
                javaClass.classLoader
                    .getResourceAsStream("scraper/zebranotheater/zebranotheater-kinderprogramm.html")!!
                    .bufferedReader()
                    .readText(),
                url
            ),
            url
        )

    @Test
    fun `reads the public performances, not the Kita plays, with their year from the weekday`() {
        events.map { it.title to it.eventDate } shouldBe
            listOf(
                "Die Schildkröte und der Wunschfelsen" to LocalDate.of(2026, 10, 11),
                "Ein ausgefuchster Fall" to LocalDate.of(2026, 11, 15),
                "Wolfswege" to LocalDate.of(2026, 11, 29),
                "Der Tanz des Pinguins" to LocalDate.of(2026, 12, 13)
            )
    }

    @Test
    fun `reads one performance in full and marks it with its age`() {
        val show = events.first()

        show.subtitle shouldBe "Kindertheater Silflay"
        show.description shouldBe "Was kann die Schildkröte tun, wenn sie zu langsam für diese Welt ist?"
        show.eventType shouldBe EventType.SHOW.name
        show.startTime shouldBe LocalTime.of(16, 0)
        show.priceBoxOffice?.compareTo(BigDecimal("7.00")) shouldBe 0
        show.sourceUrl shouldBe "https://www.zebrano-theater.de/kinderprogramm/silflay-die_schildkroete_und_der_wunschfelsen.shtml"
        show.sourceId shouldBe "zebrano_theater:kinder-20261011-1600"
        show.forChildrenPhrase shouldBe "ab 3 Jahren"
    }
}
