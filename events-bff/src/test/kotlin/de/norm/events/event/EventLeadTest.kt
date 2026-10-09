package de.norm.events.event

import de.norm.events.BaseControllerTest
import de.norm.events.ClockConfiguration
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.springframework.r2dbc.core.await
import java.time.LocalDate

/** The curated pick that leads the first page of `/events` (#1262). */
class EventLeadTest : BaseControllerTest() {
    private val today: LocalDate = LocalDate.now(ClockConfiguration.BERLIN)

    @Test
    fun `the first page leads with the earliest featured event that matches`(): Unit =
        runBlocking {
            val venueId = insertVenue("Astra", "astra")
            insertEvent(venueId, "Unpicked", "unpicked", today.plusDays(1))
            feature(insertEvent(venueId, "Later pick", "later-pick", today.plusDays(9)), "7 days")
            feature(insertEvent(venueId, "Earlier pick", "earlier-pick", today.plusDays(5)), "7 days")

            leadSlug("/events") shouldBe "earlier-pick"
        }

    @Test
    fun `a pick whose window has passed leads nothing`(): Unit =
        runBlocking {
            val venueId = insertVenue("Astra", "astra")
            feature(insertEvent(venueId, "Stale pick", "stale-pick", today.plusDays(2)), "-1 minute")

            leadSlug("/events").shouldBeNull()
        }

    @Test
    fun `a pick the filters leave out leads nothing, and the next matching pick leads instead`(): Unit =
        runBlocking {
            val venueId = insertVenue("Astra", "astra")
            feature(insertEvent(venueId, "Rave pick", "rave-pick", today.plusDays(1), eventType = "PARTY"), "7 days")
            insertEvent(venueId, "Gig", "gig", today.plusDays(2), eventType = "CONCERT")

            leadSlug("/events?eventType=CONCERT").shouldBeNull()
            leadSlug("/events?eventType=PARTY") shouldBe "rave-pick"

            feature(insertEvent(venueId, "Gig pick", "gig-pick", today.plusDays(3), eventType = "CONCERT"), "7 days")
            responseCache.clear()
            leadSlug("/events?eventType=CONCERT") shouldBe "gig-pick"
        }

    @Test
    fun `a text query matches the pick strictly, not by similarity`(): Unit =
        runBlocking {
            val venueId = insertVenue("Astra", "astra")
            feature(insertEvent(venueId, "Berghain Klubnacht", "klubnacht", today.plusDays(1)), "7 days")

            leadSlug("/events?q=berghain") shouldBe "klubnacht"
            // The typo finds the row by similarity alone: the page shows it, and the lead stays empty.
            val typo = page("/events?q=berghian")
            @Suppress("UNCHECKED_CAST")
            (typo["content"] as List<Map<String, Any?>>).map { it["slug"] } shouldBe listOf("klubnacht")
            typo["lead"].shouldBeNull()
        }

    @Test
    fun `a later page carries no lead`(): Unit =
        runBlocking {
            val venueId = insertVenue("Astra", "astra")
            feature(insertEvent(venueId, "Pick", "pick", today.plusDays(1)), "7 days")
            insertEvent(venueId, "Other", "other", today.plusDays(2))

            leadSlug("/events?size=1") shouldBe "pick"
            leadSlug("/events?size=1&page=1").shouldBeNull()
        }

    @Test
    fun `a list with no pick has a null lead`(): Unit =
        runBlocking {
            val venueId = insertVenue("Astra", "astra")
            insertEvent(venueId, "Gig", "gig", today.plusDays(1))

            leadSlug("/events").shouldBeNull()
        }

    private suspend fun feature(
        eventId: Long,
        interval: String
    ) {
        databaseClient
            .sql("UPDATE events.event SET featured_until = now() + CAST(:interval AS INTERVAL) WHERE id = :id")
            .bind("interval", interval)
            .bind("id", eventId)
            .await()
    }

    private fun page(uri: String): Map<*, *> =
        webTestClient
            .get()
            .uri(uri)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody(Map::class.java)
            .returnResult()
            .responseBody!!

    private fun leadSlug(uri: String): Any? = (page(uri)["lead"] as Map<*, *>?)?.get("slug")
}
