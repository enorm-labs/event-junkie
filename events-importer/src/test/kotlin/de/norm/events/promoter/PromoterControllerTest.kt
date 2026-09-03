package de.norm.events.promoter

import de.norm.events.BaseControllerTest
import de.norm.events.common.PageResponse
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.test.web.reactive.server.expectBody

class PromoterControllerTest : BaseControllerTest() {
    /** Creates a promoter via the API and returns the persisted [PromoterResponse]. */
    private fun createPromoter(request: PromoterRequest = PromoterRequestFixtures.concerts36()): PromoterResponse =
        webTestClient
            .post()
            .uri("/api/admin/promoters")
            .bodyValue(request)
            .exchange()
            .expectStatus()
            .isCreated
            .expectBody<PromoterResponse>()
            .returnResult()
            .responseBody!!

    private fun deletePromoter(id: Long) {
        webTestClient
            .delete()
            .uri("/api/admin/promoters/$id")
            .exchange()
            .expectStatus()
            .isNoContent
    }

    @Test
    fun `POST, GET, PUT, DELETE promoter lifecycle`() {
        // Create
        val created = createPromoter()

        created.name shouldBe "36 Concerts"
        created.slug shouldBe "36-concerts"

        val id = created.id

        // Read
        webTestClient
            .get()
            .uri("/api/admin/promoters/$id")
            .exchange()
            .expectStatus()
            .isOk
            .expectBody<PromoterResponse>()
            .consumeWith { result ->
                result.responseBody!!.name shouldBe "36 Concerts"
            }

        // Update
        webTestClient
            .put()
            .uri("/api/admin/promoters/$id")
            .bodyValue(PromoterRequestFixtures.concerts36(name = "36 Concerts GmbH"))
            .exchange()
            .expectStatus()
            .isOk
            .expectBody<PromoterResponse>()
            .consumeWith { result ->
                val promoter = result.responseBody!!
                promoter.name shouldBe "36 Concerts GmbH"
                promoter.slug shouldBe "36-concerts-gmbh"
            }

        // Delete
        deletePromoter(id)

        // Verify deleted
        webTestClient
            .get()
            .uri("/api/admin/promoters/$id")
            .exchange()
            .expectStatus()
            .isNotFound
    }

    @Test
    fun `GET promoter by non-existent ID returns 404`() {
        webTestClient
            .get()
            .uri("/api/admin/promoters/99999")
            .exchange()
            .expectStatus()
            .isNotFound
    }

    @Test
    fun `GET all promoters returns list`() {
        val created = createPromoter(PromoterRequestFixtures.create(name = "Goodlive"))

        val promoters =
            webTestClient
                .get()
                .uri("/api/admin/promoters")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody<PageResponse<PromoterResponse>>()
                .returnResult()
                .responseBody!!

        // `.content`, never `.size`: on the envelope that field is the *page* size, so an assertion
        // written against it passes whatever the listing returned (#810).
        promoters.content.size shouldBeGreaterThanOrEqual 1
        promoters.content.map { it.id } shouldContain created.id
        // Everything created here fits on one page, so the total must equal what came back.
        promoters.totalElements shouldBe promoters.content.size.toLong()
    }

    @Test
    fun `POST promoter with duplicate name returns 409 with descriptive message`() {
        createPromoter(PromoterRequestFixtures.concerts36())

        // Second promoter with the same name should conflict on slug
        webTestClient
            .post()
            .uri("/api/admin/promoters")
            .bodyValue(PromoterRequestFixtures.concerts36())
            .exchange()
            .expectStatus()
            .isEqualTo(409)
            .expectBody()
            .jsonPath("$.detail")
            .isEqualTo("A promoter with slug '36-concerts' already exists (generated from name '36 Concerts')")
    }

    @Test
    fun `PUT promoter with name that collides with existing slug returns 409`() {
        val first = createPromoter(PromoterRequestFixtures.create(name = "Über Promoter"))
        val second = createPromoter(PromoterRequestFixtures.create(name = "Unique Promoter"))

        // Renaming second promoter to a name whose slug collides with the first
        webTestClient
            .put()
            .uri("/api/admin/promoters/${second.id}")
            .bodyValue(PromoterRequestFixtures.create(name = "Uber Promoter"))
            .exchange()
            .expectStatus()
            .isEqualTo(409)
            .expectBody()
            .jsonPath("$.detail")
            .isEqualTo("A promoter with slug 'uber-promoter' already exists (generated from name 'Uber Promoter')")

        // Clean up
        deletePromoter(first.id)
        deletePromoter(second.id)
    }

    @Test
    fun `POST promoter with blank name returns 400`() {
        webTestClient
            .post()
            .uri("/api/admin/promoters")
            .bodyValue(PromoterRequestFixtures.create(name = ""))
            .exchange()
            .expectStatus()
            .isBadRequest
    }

    @Test
    fun `POST promoter with whitespace-only name returns 400`() {
        webTestClient
            .post()
            .uri("/api/admin/promoters")
            .bodyValue(PromoterRequestFixtures.create(name = "   "))
            .exchange()
            .expectStatus()
            .isBadRequest
    }

    @Test
    fun `PUT promoter with blank name returns 400`() {
        val created = createPromoter()

        webTestClient
            .put()
            .uri("/api/admin/promoters/${created.id}")
            .bodyValue(PromoterRequestFixtures.create(name = ""))
            .exchange()
            .expectStatus()
            .isBadRequest
    }
}
