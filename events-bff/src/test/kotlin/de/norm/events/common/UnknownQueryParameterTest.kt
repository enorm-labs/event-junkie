package de.norm.events.common

import de.norm.events.BaseControllerTest
import de.norm.events.event.EventFilterParams
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.BeanUtils
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Pins the rejection of unknown query parameters (#815).
 *
 * The defect these guard against is not a crash but a *wider* result set: WebFlux dropped a
 * parameter it did not recognise and answered `200` with the unfiltered collection. So the
 * assertion that matters most is the one comparing a misspelt filter against the correct one — a
 * test that only checked for `400` would still pass if the filter silently stopped being applied.
 */
class UnknownQueryParameterTest : BaseControllerTest() {
    @ParameterizedTest
    @ValueSource(
        strings = [
            "/events?nonsenseParam=xyzzy",
            "/events/today?nonsenseParam=xyzzy",
            "/events/calendar?from=2026-06-01&to=2026-06-30&nonsenseParam=xyzzy",
            "/artists?nonsenseParam=xyzzy",
            "/venues?nonsenseParam=xyzzy",
            "/promoters?nonsenseParam=xyzzy",
            "/genres?nonsenseParam=xyzzy"
        ]
    )
    fun `an unknown query parameter is rejected on every collection endpoint`(uri: String) {
        webTestClient
            .get()
            .uri(uri)
            .exchange()
            .expectStatus()
            .isBadRequest
            .expectBody()
            .jsonPath("$.unknown[0]")
            .isEqualTo("nonsenseParam")
    }

    @Test
    fun `the response names the offending parameter and what is accepted`() {
        webTestClient
            .get()
            .uri("/events?venueSlug=der-weisse-hase")
            .exchange()
            .expectStatus()
            .isBadRequest
            .expectBody()
            .jsonPath("$.status")
            .isEqualTo(400)
            .jsonPath("$.unknown[0]")
            .isEqualTo("venueSlug")
            // `venue` is the real parameter and `venueSlug` the plausible guess that caused #815,
            // so the error has to make the right name discoverable.
            .jsonPath("$.accepted")
            .value<List<String>> { accepted -> assert("venue" in accepted) { "should name the real filter, was $accepted" } }
            .jsonPath("$.accepted")
            .value<List<String>> { accepted -> assert("sort" in accepted) { "should include paging, was $accepted" } }
    }

    @Test
    fun `page size and sort stay accepted on every paginated endpoint`() {
        listOf("/events", "/artists", "/venues", "/promoters").forEach { path ->
            webTestClient
                .get()
                .uri("$path?page=0&size=5&sort=name,desc")
                .exchange()
                .expectStatus()
                .isOk
        }
    }

    /**
     * Derived from the data class rather than listed here, so a filter added to [EventFilterParams]
     * is covered by this test the moment it exists — and a filter that stops being bindable fails
     * it. Values are chosen per property type: a filter rejected for its *value* would look
     * identical to one rejected for its name.
     */
    @Test
    fun `every bindable filter on EventFilterParams is accepted`() {
        val filters =
            BeanUtils
                .getPropertyDescriptors(EventFilterParams::class.java)
                .filterNot { it.name == "class" }

        assert(filters.isNotEmpty()) { "reflection found no filters — the derivation is broken, not the endpoint" }

        filters.forEach { property ->
            val value =
                when (property.propertyType) {
                    Boolean::class.java, Boolean::class.javaObjectType -> "true"
                    BigDecimal::class.java -> "10"
                    else -> "x"
                }
            webTestClient
                .get()
                .uri("/events?${property.name}=$value")
                .exchange()
                .expectStatus()
                .isOk
        }
    }

    @Test
    fun `a misspelt filter does not silently widen the result set`(): Unit =
        runBlocking {
            val lido = insertVenue(name = "Lido", slug = "lido")
            val hase = insertVenue(name = "Weisse Hase", slug = "der-weisse-hase")
            val today = LocalDate.now()
            insertEvent(venueId = lido, title = "At Lido", slug = "at-lido", eventDate = today)
            insertEvent(venueId = hase, title = "At Hase", slug = "at-hase", eventDate = today)

            // The correct spelling narrows to one of the two events.
            webTestClient
                .get()
                .uri("/events?venue=der-weisse-hase")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$.totalElements")
                .isEqualTo(1)

            // The plausible misspelling used to return both, reported as success.
            webTestClient
                .get()
                .uri("/events?venueSlug=der-weisse-hase")
                .exchange()
                .expectStatus()
                .isBadRequest
        }
}
