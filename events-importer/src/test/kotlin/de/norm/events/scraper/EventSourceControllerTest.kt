package de.norm.events.scraper

import de.norm.events.BaseControllerTest
import de.norm.events.common.PageResponse
import de.norm.events.venue.VenueRequest
import de.norm.events.venue.VenueRequestFixtures
import de.norm.events.venue.VenueResponse
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.reactive.server.expectBody

class EventSourceControllerTest : BaseControllerTest() {
    @Autowired
    private lateinit var eventSourceRepository: EventSourceRepository

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

    /** Creates an event source via the API and returns the persisted [EventSourceResponse]. */
    private fun createSource(request: EventSourceCreateRequest): EventSourceResponse =
        webTestClient
            .post()
            .uri("/api/admin/event-sources")
            .bodyValue(request)
            .exchange()
            .expectStatus()
            .isCreated
            .expectBody<EventSourceResponse>()
            .returnResult()
            .responseBody!!

    @Test
    fun `POST, GET, PATCH, DELETE event source lifecycle`() {
        val venueId = createVenue().id

        // Create
        val created = createSource(EventSourceRequestFixtures.cassiopeia(venueId = venueId))

        created.name shouldBe "Cassiopeia Website"
        created.slug shouldBe "cassiopeia-website"
        created.venueId shouldBe venueId
        created.enabled shouldBe true
        created.status shouldBe "IDLE"
        created.importIntervalMinutes shouldBe 1440
        created.maxRetries shouldBe 3
        created.retryCount shouldBe 0

        val slug = created.slug

        // Read
        webTestClient
            .get()
            .uri("/api/admin/event-sources/$slug")
            .exchange()
            .expectStatus()
            .isOk
            .expectBody<EventSourceResponse>()
            .consumeWith { result ->
                result.responseBody!!.name shouldBe "Cassiopeia Website"
            }

        // Update (PATCH)
        webTestClient
            .patch()
            .uri("/api/admin/event-sources/$slug")
            .bodyValue(EventSourceUpdateRequest(enabled = false, importIntervalMinutes = 720))
            .exchange()
            .expectStatus()
            .isOk
            .expectBody<EventSourceResponse>()
            .consumeWith { result ->
                val source = result.responseBody!!
                source.enabled shouldBe false
                source.importIntervalMinutes shouldBe 720
                // maxRetries should remain unchanged
                source.maxRetries shouldBe 3
            }

        // Delete
        webTestClient
            .delete()
            .uri("/api/admin/event-sources/$slug")
            .exchange()
            .expectStatus()
            .isNoContent

        // Verify deleted
        webTestClient
            .get()
            .uri("/api/admin/event-sources/$slug")
            .exchange()
            .expectStatus()
            .isNotFound
    }

    @Test
    fun `GET source by non-existent slug returns 404`() {
        webTestClient
            .get()
            .uri("/api/admin/event-sources/non-existent")
            .exchange()
            .expectStatus()
            .isNotFound
    }

    @Test
    fun `GET all sources returns list`() {
        val venueId = createVenue().id
        val created = createSource(EventSourceRequestFixtures.cassiopeia(venueId = venueId))

        val sources =
            webTestClient
                .get()
                .uri("/api/admin/event-sources")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody<PageResponse<EventSourceResponse>>()
                .returnResult()
                .responseBody!!

        // `.content`, never `.size`: on the envelope that field is the *page* size, so an assertion
        // written against it passes whatever the listing returned (#810).
        sources.content.size shouldBeGreaterThanOrEqual 1
        sources.content.map { it.id } shouldContain created.id
        // Everything created here fits on one page, so the total must equal what came back.
        sources.totalElements shouldBe sources.content.size.toLong()
    }

    @Test
    fun `POST source with blank name returns 400 with structured field errors`() {
        val venueId = createVenue().id

        webTestClient
            .post()
            .uri("/api/admin/event-sources")
            .bodyValue(EventSourceRequestFixtures.create(venueId = venueId, name = ""))
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
    fun `POST source with blank URL returns 400`() {
        val venueId = createVenue().id

        webTestClient
            .post()
            .uri("/api/admin/event-sources")
            .bodyValue(EventSourceRequestFixtures.create(venueId = venueId, url = ""))
            .exchange()
            .expectStatus()
            .isBadRequest
    }

    @Test
    fun `POST source with blank source type returns 400`() {
        val venueId = createVenue().id

        webTestClient
            .post()
            .uri("/api/admin/event-sources")
            .bodyValue(EventSourceRequestFixtures.create(venueId = venueId, sourceType = ""))
            .exchange()
            .expectStatus()
            .isBadRequest
    }

    @Test
    fun `POST source with invalid source type returns 400`() {
        val venueId = createVenue().id

        webTestClient
            .post()
            .uri("/api/admin/event-sources")
            .bodyValue(EventSourceRequestFixtures.create(venueId = venueId, sourceType = "NONEXISTENT"))
            .exchange()
            .expectStatus()
            .isBadRequest
            .expectBody()
            .jsonPath("$.detail")
            .value<String> { it shouldBe "Unknown source type 'NONEXISTENT'. Valid values: ${EventSource.entries.joinToString { e -> e.name }}" }
    }

    @Test
    fun `POST import for non-existent source returns 404`() {
        // The trigger validates the slug synchronously before launching the background job,
        // so an unknown slug surfaces as 404 rather than a silent background failure.
        webTestClient
            .post()
            .uri("/api/admin/event-sources/non-existent/import")
            .exchange()
            .expectStatus()
            .isNotFound
    }

    @Test
    fun `PATCH non-existent source returns 404`() {
        webTestClient
            .patch()
            .uri("/api/admin/event-sources/non-existent")
            .bodyValue(EventSourceUpdateRequest(enabled = false))
            .exchange()
            .expectStatus()
            .isNotFound
    }

    @Test
    fun `DELETE non-existent source returns 404`() {
        webTestClient
            .delete()
            .uri("/api/admin/event-sources/non-existent")
            .exchange()
            .expectStatus()
            .isNotFound
    }

    @Test
    fun `POST retry resets failed source to IDLE`() {
        val venueId = createVenue().id
        val created = createSource(EventSourceRequestFixtures.cassiopeia(venueId = venueId))

        // Set the source to FAILED state via the repository to simulate a real failure scenario
        runBlocking {
            val entity = eventSourceRepository.findBySlug(created.slug)!!
            eventSourceRepository.save(
                entity.copy(
                    status = ImportStatus.FAILED.name,
                    retryCount = 2,
                    lastError = "Simulated import failure"
                )
            )
        }

        val retried =
            webTestClient
                .post()
                .uri("/api/admin/event-sources/${created.slug}/retry")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody<EventSourceResponse>()
                .returnResult()
                .responseBody!!

        retried.status shouldBe "IDLE"
        retried.retryCount shouldBe 0
        retried.lastError shouldBe null
    }

    @Test
    fun `POST retry for non-existent source returns 404`() {
        webTestClient
            .post()
            .uri("/api/admin/event-sources/non-existent/retry")
            .exchange()
            .expectStatus()
            .isNotFound
    }

    @Test
    fun `POST retry all resets failed sources to IDLE and returns count`() {
        val venueId = createVenue().id

        // Create two sources and simulate failures via the repository
        val source1 = createSource(EventSourceRequestFixtures.create(venueId = venueId, name = "Source One"))
        val source2 = createSource(EventSourceRequestFixtures.create(venueId = venueId, name = "Source Two"))

        runBlocking {
            val entity1 = eventSourceRepository.findBySlug(source1.slug)!!
            eventSourceRepository.save(
                entity1.copy(status = ImportStatus.FAILED.name, retryCount = 2, lastError = "Failure 1")
            )
            val entity2 = eventSourceRepository.findBySlug(source2.slug)!!
            eventSourceRepository.save(
                entity2.copy(status = ImportStatus.FAILED.name, retryCount = 1, lastError = "Failure 2")
            )
        }

        // Trigger retryAll
        val response =
            webTestClient
                .post()
                .uri("/api/admin/event-sources/retry")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody<Map<String, Int>>()
                .returnResult()
                .responseBody!!

        response["resetCount"] shouldBe 2

        // Verify both sources are now IDLE
        webTestClient
            .get()
            .uri("/api/admin/event-sources/${source1.slug}")
            .exchange()
            .expectStatus()
            .isOk
            .expectBody<EventSourceResponse>()
            .consumeWith { result ->
                val source = result.responseBody!!
                source.status shouldBe "IDLE"
                source.retryCount shouldBe 0
                source.lastError shouldBe null
            }

        webTestClient
            .get()
            .uri("/api/admin/event-sources/${source2.slug}")
            .exchange()
            .expectStatus()
            .isOk
            .expectBody<EventSourceResponse>()
            .consumeWith { result ->
                val source = result.responseBody!!
                source.status shouldBe "IDLE"
                source.retryCount shouldBe 0
                source.lastError shouldBe null
            }
    }

    @Test
    fun `POST retry all returns zero when no sources are failed`() {
        val response =
            webTestClient
                .post()
                .uri("/api/admin/event-sources/retry")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody<Map<String, Int>>()
                .returnResult()
                .responseBody!!

        response["resetCount"] shouldBe 0
    }

    @Test
    fun `POST source with non-existent venue ID returns 404`() {
        webTestClient
            .post()
            .uri("/api/admin/event-sources")
            .bodyValue(EventSourceRequestFixtures.cassiopeia(venueId = 999_999))
            .exchange()
            .expectStatus()
            .isNotFound
    }

    @Test
    fun `POST source with reserved slug 'import' returns 400`() {
        val venueId = createVenue().id

        webTestClient
            .post()
            .uri("/api/admin/event-sources")
            .bodyValue(EventSourceRequestFixtures.create(venueId = venueId, name = "Import"))
            .exchange()
            .expectStatus()
            .isBadRequest
    }

    @Test
    fun `POST source with reserved slug 'retry' returns 400`() {
        val venueId = createVenue().id

        webTestClient
            .post()
            .uri("/api/admin/event-sources")
            .bodyValue(EventSourceRequestFixtures.create(venueId = venueId, name = "Retry"))
            .exchange()
            .expectStatus()
            .isBadRequest
    }
}
