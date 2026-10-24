package de.norm.events.event

import de.norm.events.BaseControllerTest
import de.norm.events.common.BERLIN
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.r2dbc.core.await
import java.time.LocalDate

/**
 * A `for-children` event stays out of a list that does not ask for it: the feature, a venue, an
 * artist, a promoter, a festival or a text asks for it (#3796). Its own page still shows it.
 */
class EventForChildrenFilterTest : BaseControllerTest() {
    private val today = LocalDate.now(BERLIN)

    @BeforeEach
    fun seed(): Unit =
        runBlocking {
            val theatreId = insertVenue("Theater", "theater")
            insertEvent(theatreId, "Evening Play", "evening-play", today, eventType = "SHOW")
            val kasper = insertEvent(theatreId, "Kasper und das Krokodil", "kasper", today.plusDays(1), eventType = "SHOW")
            databaseClient
                .sql("INSERT INTO events.event_feature (event_id, feature, matched_phrase) VALUES (:id, 'for-children', 'für Kinder')")
                .bind("id", kasper)
                .await()
        }

    @Test
    fun `GET events leaves a children's show out unless the request asks for it`() {
        mapOf(
            "" to listOf("evening-play"),
            "eventType=SHOW" to listOf("evening-play"),
            "feature=for-children" to listOf("kasper"),
            "venue=theater" to listOf("evening-play", "kasper"),
            "q=Kasper" to listOf("kasper")
        ).forEach { (query, slugs) ->
            webTestClient
                .get()
                .uri("/events?$query")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$.totalElements")
                .isEqualTo(slugs.size)
                .jsonPath("$.content[*].slug")
                .isEqualTo(slugs)
        }
    }

    @Test
    fun `GET events calendar leaves a children's show out unless the request asks for it`() {
        listOf("" to listOf("evening-play"), "&feature=for-children" to listOf("kasper")).forEach { (query, slugs) ->
            webTestClient
                .get()
                .uri("/events/calendar?from=$today&to=${today.plusDays(7)}$query")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$[*].slug")
                .isEqualTo(slugs)
        }
    }

    @Test
    fun `GET event detail shows a children's show with its feature`() {
        webTestClient
            .get()
            .uri("/events/kasper")
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.features")
            .isEqualTo(listOf("for-children"))
    }
}
