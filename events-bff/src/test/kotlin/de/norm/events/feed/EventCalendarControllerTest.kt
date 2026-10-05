package de.norm.events.feed

import de.norm.events.BaseControllerTest
import de.norm.events.ClockConfiguration
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.springframework.http.CacheControl
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.r2dbc.core.await
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

class EventCalendarControllerTest : BaseControllerTest() {
    private val today = LocalDate.now(ClockConfiguration.BERLIN)

    /** Ten days ahead: inside the window, and never over during a run. */
    private val night = today.plusDays(10)

    private val cacheLifetime = CacheControl.maxAge(Duration.ofSeconds(60)).cachePublic()

    private fun ics(query: String = ""): String =
        webTestClient
            .get()
            .uri("/events/calendar.ics$query")
            .exchange()
            .expectStatus()
            .isOk
            .expectHeader()
            .contentTypeCompatibleWith(MediaType.parseMediaType("text/calendar"))
            .expectHeader()
            .cacheControl(cacheLifetime)
            .expectBody(String::class.java)
            .returnResult()
            .responseBody!!

    /** The content lines with their folds undone, as a client reads them. */
    private fun lines(ics: String): List<String> = ics.replace("\r\n ", "").removeSuffix("\r\n").split("\r\n")

    /** Each `VEVENT` as its property lines, in the order of the file. */
    private fun vevents(ics: String): List<List<String>> {
        val events = mutableListOf<List<String>>()
        val current = mutableListOf<String>()
        var inside = false
        lines(ics).forEach { line ->
            when (line) {
                "BEGIN:VEVENT" -> {
                    current.clear()
                    inside = true
                }

                "END:VEVENT" -> {
                    events += current.toList()
                    inside = false
                }

                else -> {
                    if (inside) current += line
                }
            }
        }
        return events
    }

    private fun List<String>.value(name: String): String? = firstOrNull { it.startsWith("$name:") || it.startsWith("$name;") }?.substringAfter(':')

    private fun utc(
        date: LocalDate,
        time: LocalTime
    ): String =
        DateTimeFormatter
            .ofPattern("yyyyMMdd'T'HHmmss'Z'")
            .format(date.atTime(time).atZone(ClockConfiguration.BERLIN).withZoneSameInstant(ZoneOffset.UTC))

    private suspend fun setColumn(
        eventId: Long,
        column: String,
        value: String
    ) {
        databaseClient
            .sql("UPDATE events.event SET $column = :value WHERE id = :id")
            .bind("value", value)
            .bind("id", eventId)
            .await()
    }

    @Test
    fun `lists the coming days in order, one entry per event with a stable UID, and leaves out the rest`(): Unit =
        runBlocking {
            val venueId = insertVenue("Lido", "lido", address = "Cuvrystr. 7")
            insertEvent(venueId, "Later", "later", night.plusDays(1), startTime = LocalTime.of(21, 0))
            val first = insertEvent(venueId, "First", "first", night, startTime = LocalTime.of(20, 0), subtitle = "Tour, 2099")
            insertEvent(venueId, "Over", "over", today.minusDays(2), startTime = LocalTime.of(20, 0))
            insertEvent(venueId, "Too Far", "too-far", today.plusDays(90), startTime = LocalTime.of(20, 0))
            setColumn(first, "room", "Saal")

            val body = ics()

            body shouldStartWith "BEGIN:VCALENDAR\r\nVERSION:2.0\r\n"
            body shouldEndWith "END:VCALENDAR\r\n"
            body.replace("\r\n", "") shouldNotContain "\n"
            val events = vevents(body)
            events.map { it.value("SUMMARY") } shouldContainExactly listOf("First", "Later")
            val entry = events.first()
            entry.value("UID") shouldBe "first@event-junkie.de"
            entry.value("DTSTART") shouldBe utc(night, LocalTime.of(20, 0))
            entry.value("DTEND") shouldBe utc(night, LocalTime.of(23, 0))
            entry.value("LOCATION") shouldBe "Lido\\, Saal\\, Cuvrystr. 7\\, Berlin"
            entry.value("URL") shouldBe "https://event-junkie.de/en/events/first"
            entry.value("DESCRIPTION") shouldBe
                "Tour\\, 2099\\n\\nThe venue states no end time\\, so the calendar entry ends at our estimate.\\n\\n" +
                "https://event-junkie.de/en/events/first"
            entry.value("STATUS") shouldBe null
        }

    @Test
    fun `keeps a stated end, guesses a missing start, and draws a run of days without times as whole days`(): Unit =
        runBlocking {
            val venueId = insertVenue("Lido", "lido")
            insertEvent(
                venueId,
                "Stated",
                "stated",
                night,
                startTime = LocalTime.of(23, 0),
                endDate = night.plusDays(1),
                endTime = LocalTime.of(6, 0)
            )
            insertEvent(venueId, "Guessed", "guessed", night.plusDays(1), eventType = "PARTY")
            insertEvent(venueId, "Run", "run", today.minusDays(5), eventType = "EXHIBITION", endDate = night)

            val byTitle = vevents(ics("?locale=de")).associateBy { it.value("SUMMARY") }

            byTitle.getValue("Stated").value("DTEND") shouldBe utc(night.plusDays(1), LocalTime.of(6, 0))
            byTitle.getValue("Stated").value("DESCRIPTION") shouldBe "https://event-junkie.de/de/events/stated"
            byTitle.getValue("Guessed").value("DTSTART") shouldBe utc(night.plusDays(1), LocalTime.of(23, 0))
            byTitle.getValue("Guessed").value("DESCRIPTION")!! shouldStartWith "Die Startzeit ist unsere Schätzung.\\n\\nDie Location nennt kein Ende"
            val run = byTitle.getValue("Run")
            run shouldContain "DTSTART;VALUE=DATE:${today.minusDays(5).format(DateTimeFormatter.BASIC_ISO_DATE)}"
            run shouldContain "DTEND;VALUE=DATE:${night.plusDays(1).format(DateTimeFormatter.BASIC_ISO_DATE)}"
        }

    @Test
    fun `strikes out a cancelled event, and marks a postponed one tentative with the reason`(): Unit =
        runBlocking {
            val venueId = insertVenue("Lido", "lido")
            setColumn(insertEvent(venueId, "Cancelled", "cancelled", night), "status", "CANCELLED")
            setColumn(insertEvent(venueId, "Postponed", "postponed", night), "status", "POSTPONED")
            insertEvent(venueId, "On", "on", night)

            val byTitle = vevents(ics()).associateBy { it.value("SUMMARY") }

            byTitle.getValue("Cancelled").value("STATUS") shouldBe "CANCELLED"
            byTitle.getValue("Postponed").value("STATUS") shouldBe "TENTATIVE"
            byTitle.getValue("Postponed").value("DESCRIPTION")!! shouldStartWith "Postponed: the venue names no new date yet."
            byTitle.getValue("On").value("STATUS") shouldBe null
        }

    @Test
    fun `sends a relocated event to the house it moved to`(): Unit =
        runBlocking {
            val venueId = insertVenue("Hole44", "hole44")
            val moved = insertEvent(venueId, "Moved", "moved", night)
            setColumn(moved, "status", "RELOCATED")
            setColumn(moved, "relocated_to", "Säälchen")

            val en = vevents(ics()).single()
            en.value("LOCATION") shouldBe "Moved to Säälchen\\, was Hole44"
            en.value("DESCRIPTION")!! shouldStartWith "Moved to Säälchen."
            en.value("STATUS") shouldBe null

            vevents(ics("?locale=de")).single().value("LOCATION") shouldBe "Verlegt: Säälchen\\, vorher Hole44"
        }

    @Test
    fun `takes the list's filters`(): Unit =
        runBlocking {
            val lido = insertVenue("Lido", "lido")
            val astra = insertVenue("Astra", "astra")
            val jazz = insertGenreTag("Jazz", "jazz")
            linkGenre(insertEvent(lido, "Lido Jazz", "lido-jazz", night), jazz)
            insertEvent(lido, "Lido Folk", "lido-folk", night)
            linkGenre(insertEvent(astra, "Astra Jazz", "astra-jazz", night), jazz)

            vevents(ics("?genre=jazz&venue=lido")).map { it.value("SUMMARY") } shouldContainExactly listOf("Lido Jazz")
        }

    @Test
    fun `takes the time-of-night filter, by overlap`(): Unit =
        runBlocking {
            val lido = insertVenue("Lido", "lido")
            insertEvent(lido, "Matinee", "matinee", night, startTime = LocalTime.of(11, 0))
            insertEvent(lido, "Rave", "rave", night.plusDays(1), eventType = "PARTY", startTime = LocalTime.of(23, 0))
            insertEvent(
                lido,
                "Open Air",
                "open-air",
                night.plusDays(2),
                startTime = LocalTime.of(14, 0),
                endDate = night.plusDays(2),
                endTime = LocalTime.of(23, 0)
            )

            vevents(ics("?timeOfDay=late")).map { it.value("SUMMARY") } shouldContainExactly listOf("Rave", "Open Air")
        }

    @Test
    fun `caps the calendar at the first five hundred`(): Unit =
        runBlocking {
            val venueId = insertVenue("Lido", "lido")
            databaseClient
                .sql(
                    "INSERT INTO events.event (venue_id, title, slug, event_date, start_time, source_id, event_type) " +
                        "SELECT :venueId, 'Night ' || n, 'night-' || n, :day, TIME '00:00' + n * INTERVAL '1 minute', 'test:night-' || n, 'CONCERT' " +
                        "FROM generate_series(1, 501) AS n"
                ).bind("venueId", venueId)
                .bind("day", night)
                .await()

            val titles = vevents(ics()).map { it.value("SUMMARY") }

            titles shouldHaveSize 500
            titles.first() shouldBe "Night 1"
            titles.last() shouldBe "Night 500"
        }

    @Test
    fun `answers a client that already has this version with 304, keeping the cache lifetime`(): Unit =
        runBlocking {
            insertEvent(insertVenue("Lido", "lido"), "Night", "night", night)
            val etag =
                webTestClient
                    .get()
                    .uri("/events/calendar.ics")
                    .exchange()
                    .expectStatus()
                    .isOk
                    .returnResult(String::class.java)
                    .responseHeaders.eTag!!
            etag shouldStartWith "W/\""

            webTestClient
                .get()
                .uri("/events/calendar.ics")
                .header(HttpHeaders.IF_NONE_MATCH, etag)
                .exchange()
                .expectStatus()
                .isNotModified
                .expectHeader()
                .cacheControl(cacheLifetime)
        }

    @Test
    fun `rejects a locale it has no text for, and a parameter the list does not take`() {
        webTestClient
            .get()
            .uri("/events/calendar.ics?locale=fr")
            .exchange()
            .expectStatus()
            .isBadRequest
        webTestClient
            .get()
            .uri("/events/calendar.ics?from=2099-01-01")
            .exchange()
            .expectStatus()
            .isBadRequest
    }
}
