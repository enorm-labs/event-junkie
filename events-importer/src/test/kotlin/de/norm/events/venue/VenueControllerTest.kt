package de.norm.events.venue

import de.norm.events.BaseControllerTest
import de.norm.events.common.PageResponse
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.test.web.reactive.server.expectBody

class VenueControllerTest : BaseControllerTest() {
    /** Creates a venue via the API and returns the persisted [VenueResponse]. */
    private fun createVenue(request: VenueRequest = VenueRequestFixtures.astra()): VenueResponse =
        webTestClient
            .post()
            .uri("/api/admin/venues")
            .bodyValue(request)
            .exchange()
            .expectStatus()
            .isCreated
            .expectBody<VenueResponse>()
            .returnResult()
            .responseBody!!

    private fun deleteVenue(id: Long) {
        webTestClient
            .delete()
            .uri("/api/admin/venues/$id")
            .exchange()
            .expectStatus()
            .isNoContent
    }

    @Test
    fun `POST, GET, PUT, DELETE venue lifecycle`() {
        // Create
        val created = createVenue()

        created.name shouldBe "Astra Kulturhaus"
        created.slug shouldBe "astra-kulturhaus"

        val id = created.id

        // Read
        webTestClient
            .get()
            .uri("/api/admin/venues/$id")
            .exchange()
            .expectStatus()
            .isOk
            .expectBody<VenueResponse>()
            .consumeWith { result ->
                val venue = result.responseBody!!
                venue.name shouldBe "Astra Kulturhaus"
                // District round-trips through create → persist → read.
                venue.district shouldBe "friedrichshain-kreuzberg"
                // Description round-trips through create → persist → read.
                venue.description shouldBe "A large concert hall on the RAW-Gelände in Friedrichshain."
            }

        // Update
        webTestClient
            .put()
            .uri("/api/admin/venues/$id")
            .bodyValue(VenueRequestFixtures.astra(name = "Astra Berlin"))
            .exchange()
            .expectStatus()
            .isOk
            .expectBody<VenueResponse>()
            .consumeWith { result ->
                val venue = result.responseBody!!
                venue.name shouldBe "Astra Berlin"
                venue.slug shouldBe "astra-berlin"
            }

        // Delete
        deleteVenue(id)

        // Verify deleted
        webTestClient
            .get()
            .uri("/api/admin/venues/$id")
            .exchange()
            .expectStatus()
            .isNotFound
    }

    @Test
    fun `GET venue by non-existent ID returns 404`() {
        webTestClient
            .get()
            .uri("/api/admin/venues/99999")
            .exchange()
            .expectStatus()
            .isNotFound
    }

    @Test
    fun `GET venue by non-numeric ID returns 400, not 500`() {
        // Passing a slug where the API takes a numeric id is a client error. WebFlux raises a
        // ServerWebInputException carrying 400, but its cause chain ends in a NumberFormatException;
        // before GlobalExceptionHandler.handleInvalidInput existed, the cause-chain fallback let the
        // IllegalArgumentException handler answer 500 with the raw converter message instead.
        webTestClient
            .get()
            .uri("/api/admin/venues/bar-jeder-vernunft")
            .exchange()
            .expectStatus()
            .isBadRequest
            .expectBody()
            .jsonPath("$.detail")
            .isEqualTo("Invalid value 'bar-jeder-vernunft': expected a valid Long.")
    }

    @Test
    fun `DELETE venue by non-numeric ID returns 400, not 500`() {
        // The handler is global, so every /{id} endpoint and verb is covered by the same fix.
        webTestClient
            .delete()
            .uri("/api/admin/venues/bar-jeder-vernunft")
            .exchange()
            .expectStatus()
            .isBadRequest
    }

    @Test
    fun `GET all venues returns list`() {
        val created = createVenue(VenueRequestFixtures.create(name = "Cassiopeia"))

        val venues =
            webTestClient
                .get()
                .uri("/api/admin/venues")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody<PageResponse<VenueResponse>>()
                .returnResult()
                .responseBody!!

        // `.content`, never `.size`: on the envelope that field is the *page* size, so an assertion
        // written against it passes whatever the listing returned (#810).
        venues.content.size shouldBeGreaterThanOrEqual 1
        venues.content.map { it.id } shouldContain created.id
        // Everything created here fits on one page, so the total must equal what came back.
        venues.totalElements shouldBe venues.content.size.toLong()
    }

    @Test
    fun `reports the whole table when a page holds only part of it`() {
        // The case this endpoint had no test for, and the one #810 is about: a client that reads
        // once must be able to tell 3 rows from 3-of-5. Before the envelope both answers were the
        // same JSON array, and `apply-licence-review.py` wrote 20 of 86 sources reporting success.
        repeat(5) { createVenue(VenueRequestFixtures.create(name = "Truncation Venue $it")) }

        val page =
            webTestClient
                .get()
                .uri("/api/admin/venues?size=3&sort=name,asc")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody<PageResponse<VenueResponse>>()
                .returnResult()
                .responseBody!!

        page.content shouldHaveSize 3
        page.totalElements shouldBe 5L
        page.totalPages shouldBe 2
        page.page shouldBe 0
        page.size shouldBe 3
    }

    @Test
    fun `clamps an oversized page size, and the total still reports the whole table`() {
        // The wiring is what this covers: WebFluxConfiguration constructs the resolver by hand, so
        // the cap reaches a request only if it is passed in. Spring's servlet-only
        // `spring.data.web.pageable.max-page-size` cannot do it here.
        createVenue(VenueRequestFixtures.create(name = "Cap Venue"))

        val page =
            webTestClient
                .get()
                .uri("/api/admin/venues?size=5000")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody<PageResponse<VenueResponse>>()
                .returnResult()
                .responseBody!!

        page.size shouldBe 100
        page.totalElements shouldBe page.content.size.toLong()
    }

    @Test
    fun `POST venue with duplicate name returns 409 with descriptive message`() {
        createVenue(VenueRequestFixtures.astra())

        // Second venue with the same name should conflict on slug
        webTestClient
            .post()
            .uri("/api/admin/venues")
            .bodyValue(VenueRequestFixtures.astra())
            .exchange()
            .expectStatus()
            .isEqualTo(409)
            .expectBody()
            .jsonPath("$.detail")
            .isEqualTo("A venue with slug 'astra-kulturhaus' already exists (generated from name 'Astra Kulturhaus')")
    }

    @Test
    fun `PUT venue with name that collides with existing slug returns 409`() {
        val first = createVenue(VenueRequestFixtures.create(name = "Über Club"))
        val second = createVenue(VenueRequestFixtures.create(name = "Unique Venue"))

        // Renaming second venue to a name whose slug collides with the first
        webTestClient
            .put()
            .uri("/api/admin/venues/${second.id}")
            .bodyValue(VenueRequestFixtures.create(name = "Uber Club"))
            .exchange()
            .expectStatus()
            .isEqualTo(409)
            .expectBody()
            .jsonPath("$.detail")
            .isEqualTo("A venue with slug 'uber-club' already exists (generated from name 'Uber Club')")

        // Clean up
        deleteVenue(first.id)
        deleteVenue(second.id)
    }

    @Test
    fun `POST venue with blank name returns 400 with structured field errors`() {
        webTestClient
            .post()
            .uri("/api/admin/venues")
            .bodyValue(VenueRequestFixtures.create(name = ""))
            .exchange()
            .expectStatus()
            .isBadRequest
            .expectBody()
            .jsonPath("$.detail")
            .isEqualTo("Validation failed")
            .jsonPath("$.errors")
            .isArray
            .jsonPath("$.errors[?(@.field == 'name')]")
            .exists()
    }

    @Test
    fun `POST venue with whitespace-only name returns 400`() {
        webTestClient
            .post()
            .uri("/api/admin/venues")
            .bodyValue(VenueRequestFixtures.create(name = "   "))
            .exchange()
            .expectStatus()
            .isBadRequest
    }

    @Test
    fun `PUT venue with blank name returns 400`() {
        val created = createVenue()

        webTestClient
            .put()
            .uri("/api/admin/venues/${created.id}")
            .bodyValue(VenueRequestFixtures.create(name = ""))
            .exchange()
            .expectStatus()
            .isBadRequest
    }
}
