package de.norm.events.event

import de.norm.events.BaseControllerTest
import de.norm.events.common.BERLIN
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.r2dbc.core.await
import java.time.LocalDate

/**
 * A `for-children` event and a `christmas-market` event stay out of a list that does not ask for them: the feature,
 * a venue, an artist, a promoter, a festival or a text asks for one (#3796, #4972). Its own page still shows it.
 */
class EventHiddenFeatureFilterTest : BaseControllerTest() {
    private val today = LocalDate.now(BERLIN)

    @BeforeEach
    fun seed(): Unit =
        runBlocking {
            val theatreId = insertVenue("Theater", "theater")
            insertEvent(theatreId, "Evening Play", "evening-play", today, eventType = "SHOW")
            val kasper = insertEvent(theatreId, "Kasper und das Krokodil", "kasper", today.plusDays(1), eventType = "SHOW")
            insertFeature(kasper, "for-children", "für Kinder")
            val squareId = insertVenue("Marktplatz", "marktplatz")
            val market = insertEvent(squareId, "Weihnachtsmarkt", "weihnachtsmarkt", today.plusDays(2), eventType = "OTHER")
            insertFeature(market, "christmas-market", "entered by hand")
        }

    private suspend fun insertFeature(
        eventId: Long,
        feature: String,
        phrase: String
    ) = databaseClient
        .sql("INSERT INTO events.event_feature (event_id, feature, matched_phrase) VALUES (:id, :feature, :phrase)")
        .bind("id", eventId)
        .bind("feature", feature)
        .bind("phrase", phrase)
        .await()

    @Test
    fun `GET events leaves a hidden feature out unless the request asks for it`() {
        mapOf(
            "" to listOf("evening-play"),
            "eventType=SHOW" to listOf("evening-play"),
            "feature=for-children" to listOf("kasper"),
            "feature=christmas-market" to listOf("weihnachtsmarkt"),
            "venue=theater" to listOf("evening-play", "kasper"),
            "venue=marktplatz" to listOf("weihnachtsmarkt"),
            "q=Kasper" to listOf("kasper"),
            "q=Weihnachtsmarkt" to listOf("weihnachtsmarkt")
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
    fun `GET events calendar leaves a hidden feature out unless the request asks for it`() {
        listOf(
            "" to listOf("evening-play"),
            "&feature=for-children" to listOf("kasper"),
            "&feature=christmas-market" to listOf("weihnachtsmarkt")
        ).forEach { (query, slugs) ->
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
    fun `GET event detail shows a hidden event with its feature`() {
        mapOf("kasper" to "for-children", "weihnachtsmarkt" to "christmas-market").forEach { (slug, feature) ->
            webTestClient
                .get()
                .uri("/events/$slug")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$.features")
                .isEqualTo(listOf(feature))
        }
    }
}
