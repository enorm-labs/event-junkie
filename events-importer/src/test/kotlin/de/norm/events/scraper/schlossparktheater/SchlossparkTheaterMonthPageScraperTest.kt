package de.norm.events.scraper.schlossparktheater

import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime

class SchlossparkTheaterMonthPageScraperTest {
    private val scraper = SchlossparkTheaterMonthPageScraper()

    private fun scrape(month: String): List<ScrapedEvent> {
        val url = "https://www.schlossparktheater.de/spielplan/$month.html"
        val html = javaClass.classLoader.getResource("scraper/schlossparktheater/schlossparktheater-$month.html")!!.readText()
        return scraper.scrape(Jsoup.parse(html, url), url)
    }

    private val november by lazy { scrape("november-2026") }
    private val february by lazy { scrape("februar-2027") }

    private fun List<ScrapedEvent>.performance(id: Int) = single { it.sourceId == "schlosspark_theater:$id" }

    @Test
    fun `parses every performance of the month`() {
        november shouldHaveSize 39
        february shouldHaveSize 35
        november.map { it.sourceId }.distinct() shouldHaveSize 39
        november.all { it.eventDate.monthValue == 11 && it.eventDate.year == 2026 } shouldBe true
    }

    @Test
    fun `a house production is a show with its cast line as the subtitle and no billed act`() {
        val play = november.first { it.title == "Was ist los mit der Welt?!" }

        play.eventType shouldBe EventType.SHOW.name
        play.typeIsFallback shouldBe false
        play.eventDate shouldBe LocalDate.of(2026, 11, 1)
        play.startTime shouldBe LocalTime.of(16, 0)
        play.subtitle shouldBe "Komödie mit Maria Hartmann, Heinrich Schafmeister u.a."
        play.sourceUrl shouldBe "https://www.schlossparktheater.de/produktionen/was-ist-los-mit-der-welt.html"
        play.sourceId.startsWith(EventSource.SCHLOSSPARK_THEATER.sourceIdPrefix) shouldBe true
        play.ticketUrl shouldBe "https://schlossparktheater.eventim-inhouse.de/webshop/webticket/shop?event=2962"
        play.imageUrl?.startsWith("https://www.schlossparktheater.de/uploads/produktion/") shouldBe true
        play.pricePresale shouldBe BigDecimal("26.00")
        play.priceNote shouldBe "26,00–43,50 €"
        play.artists.shouldBeEmpty()
    }

    @Test
    fun `a guest act before a quoted programme is the billed act and leads the title`() {
        val reading = november.first { it.title.startsWith("Joschka Fischer") }

        reading.title shouldBe "Joschka Fischer: Wer sind wir?"
        reading.eventType shouldBe EventType.READING.name
        reading.subtitle shouldBe "Deutschland auf der Suche nach seiner Identität - Lesung -"
        reading.artists shouldContainExactly listOf(ScrapedArtist(name = "Joschka Fischer", titleDerived = true))
    }

    @Test
    fun `an unnamed band or orchestra is not an act`() {
        val concert = november.first { it.title.startsWith("Ronny Heinrich") }

        concert.title shouldBe "Ronny Heinrich und sein Orchester: Dankeschön, Peter - 100 Jahre Peter Alexander"
        concert.eventType shouldBe EventType.CONCERT.name
        concert.artists.map { it.name } shouldContainExactly listOf("Ronny Heinrich")
    }

    @Test
    fun `an introductory matinee is a free talk that bills nobody`() {
        val matinee = november.first { it.title.startsWith("Einführungsmatinée") }

        matinee.title shouldBe "Einführungsmatinée: Die Sternstunde des Josef Bieder"
        matinee.eventType shouldBe EventType.READING.name
        matinee.free shouldBe true
        matinee.pricePresale.shouldBeNull()
        matinee.artists.shouldBeEmpty()
    }

    @Test
    fun `a guest act with no format word is a fallback show`() {
        val act = november.first { it.title.startsWith("Frank Lüdecke") }

        act.title shouldBe "Frank Lüdecke: Träumt weiter!"
        act.eventType shouldBe EventType.SHOW.name
        act.typeIsFallback shouldBe true
        act.subtitle.shouldBeNull()
        act.artists.map { it.name } shouldContainExactly listOf("Frank Lüdecke")
    }

    @Test
    fun `an unquoted programme bills the act only for a concert`() {
        november.first { it.title.startsWith("Kim & Klaus") }.apply {
            title shouldBe "Kim & Klaus: Der Live Talk-Podcast"
            eventType shouldBe EventType.READING.name
            artists.shouldBeEmpty()
        }
        november.first { it.title.startsWith("Wencke Myhre") }.apply {
            title shouldBe "Wencke Myhre pur – ein Wohnzimmerkonzert"
            eventType shouldBe EventType.CONCERT.name
            artists.shouldBeEmpty()
        }
        february.first { it.title.startsWith("Karussell") }.apply {
            title shouldBe "Karussell: 50 Jahre deutsche Rockmusik"
            eventType shouldBe EventType.CONCERT.name
            artists.map { it.name } shouldContainExactly listOf("Karussell")
        }
    }

    @Test
    fun `a move note is the status note and stays out of the subtitle`() {
        val moved = february.performance(4133)

        moved.title shouldBe "Axel Prahl und Knut Elstermann: Was man liebt, braucht Zeit"
        moved.subtitle shouldBe "Zwischen Meer, Musik und Mordkommission"
        moved.statusNote shouldBe "Diese Veranstaltung wurde vom 31.01. auf den 07.02.2027 verlegt."
        moved.artists.map { it.name } shouldContainExactly listOf("Axel Prahl", "Knut Elstermann")
    }

    @Test
    fun `a heading that ends in a colon joins its programme with a space`() {
        val talk = february.first { it.title.startsWith("Körperwissen") }

        talk.title shouldBe "Körperwissen live: Ein Anatomieabenteuer über das Leben"
        talk.eventType shouldBe EventType.READING.name
        talk.artists.shouldBeEmpty()
    }

    @Test
    fun `an empty page yields no events`() {
        scraper.scrape(Jsoup.parse("<html><body><div id=spielplan></div></body></html>"), "https://example.org").shouldBeEmpty()
    }
}

class SchlossparkEventTypeTest {
    @Test
    fun `types the billing by its format words`() {
        schlossparkEventType("PUTSCH", "Anleitung zur Zerstörung einer Demokratie · Komödie mit Judith Richter") shouldBe EventType.SHOW.name
        schlossparkEventType("ABBA - Waterloo im Bällebad", "Musikalische Komödie um große Hits und schöne Möbel") shouldBe EventType.SHOW.name
        schlossparkEventType("Arnulf Rating: Die Jahrespresseschau 2026", "Kabarett in Wort und Bild") shouldBe EventType.COMEDY.name
        schlossparkEventType("Ildikó von Kürthy: Alt genug", "Gespräch, Gesang, Lesung, Showtime!") shouldBe EventType.READING.name
        schlossparkEventType("Bettina Tietjen: Tietjen sucht das Weite", "Die Bestsellerautorin über ihre Campingabenteuer") shouldBe
            EventType.READING.name
        schlossparkEventType("Die Frank Sinatra Christmas Show", "mit Roger Pabst & seinem Orchester") shouldBe EventType.CONCERT.name
        schlossparkEventType("Purple Schulz: Ich hab nicht ewig Zeit", "Die Jubiläumstour 2027") shouldBe EventType.CONCERT.name
        schlossparkEventType("Weiße Zeiten", "Eine weihnachtliche Revue") shouldBe EventType.SHOW.name
        schlossparkEventType("Duo Graceland: Simon & Garfunkel Tribute Meets Classic", null) shouldBe EventType.CONCERT.name
        schlossparkEventType("Gayle Tufts: Very Christmas", null).shouldBeNull()
    }
}
