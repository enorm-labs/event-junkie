package de.norm.events.festival

import de.norm.events.BaseControllerTest
import de.norm.events.ClockConfiguration
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.springframework.r2dbc.core.await
import java.time.LocalDate

class FestivalControllerTest : BaseControllerTest() {
    private val today: LocalDate = LocalDate.now(ClockConfiguration.BERLIN)

    private suspend fun insertFestival(
        name: String,
        firstDay: LocalDate,
        lastDay: LocalDate = firstDay
    ) {
        databaseClient
            .sql(
                "INSERT INTO events.festival (slug, name, url, first_day, last_day, notice_de, notice_en) " +
                    "VALUES (:slug, :name, :url, :firstDay, :lastDay, :noticeDe, :noticeEn)"
            ).bind("slug", name.lowercase().replace(' ', '-'))
            .bind("name", name)
            .bind("url", "https://festival.example/programme")
            .bind("firstDay", firstDay)
            .bind("lastDay", lastDay)
            .bind("noticeDe", "$name auf Deutsch.")
            .bind("noticeEn", "$name in English.")
            .await()
    }

    private fun expectCurrent(vararg slugs: String) {
        webTestClient
            .get()
            .uri("/festivals/current")
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$[*].slug")
            .isEqualTo(slugs.toList())
    }

    @Test
    fun `answers the festival running today, with both notices and its days`(): Unit =
        runBlocking {
            insertFestival("Running", today.minusDays(2), today.plusDays(6))

            webTestClient
                .get()
                .uri("/festivals/current")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$.length()")
                .isEqualTo(1)
                .jsonPath("$[0].name")
                .isEqualTo("Running")
                .jsonPath("$[0].url")
                .isEqualTo("https://festival.example/programme")
                .jsonPath("$[0].firstDay")
                .isEqualTo(today.minusDays(2).toString())
                .jsonPath("$[0].lastDay")
                .isEqualTo(today.plusDays(6).toString())
                .jsonPath("$[0].noticeDe")
                .isEqualTo("Running auf Deutsch.")
                .jsonPath("$[0].noticeEn")
                .isEqualTo("Running in English.")
        }

    @Test
    fun `answers a festival on its last day`(): Unit =
        runBlocking {
            insertFestival("Last day", today.minusDays(3), today)

            expectCurrent("last-day")
        }

    @Test
    fun `answers a festival starting in 14 days, and not one starting in 15`(): Unit =
        runBlocking {
            insertFestival("Fortnight", today.plusDays(14))
            insertFestival("Too far", today.plusDays(15), today.plusDays(20))

            expectCurrent("fortnight")
        }

    @Test
    fun `answers an empty array when there is no festival, or only one that ended yesterday`(): Unit =
        runBlocking {
            expectCurrent()

            responseCache.clear()
            insertFestival("Over", today.minusDays(5), today.minusDays(1))
            expectCurrent()
        }

    @Test
    fun `answers both of two overlapping running festivals, the earlier start first`(): Unit =
        runBlocking {
            insertFestival("Later start", today.minusDays(1), today.plusDays(9))
            insertFestival("Earliest start", today.minusDays(4), today.plusDays(1))

            expectCurrent("earliest-start", "later-start")
        }

    @Test
    fun `answers running festivals before upcoming ones`(): Unit =
        runBlocking {
            insertFestival("Upcoming", today.plusDays(3))
            insertFestival("Running", today, today.plusDays(5))

            expectCurrent("running", "upcoming")
        }

    @Test
    fun `answers at most 3 festivals, dropping the latest start`(): Unit =
        runBlocking {
            insertFestival("Fourth", today.plusDays(10))
            insertFestival("Third", today.plusDays(5))
            insertFestival("Second", today.plusDays(1))
            insertFestival("First", today.minusDays(2), today.plusDays(2))

            expectCurrent("first", "second", "third")
        }
}
