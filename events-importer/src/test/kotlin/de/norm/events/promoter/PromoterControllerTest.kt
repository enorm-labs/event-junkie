package de.norm.events.promoter

import de.norm.events.BaseControllerTest
import de.norm.events.common.PageResponse
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.test.web.reactive.server.expectBody
import java.time.Instant

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
                val promoter = result.responseBody!!
                promoter.name shouldBe "36 Concerts"
                // Both descriptions round-trip through create → persist → read.
                promoter.description shouldBe "The in-house agency of Lido, Astra and Bi Nuu."
                promoter.descriptionLanguage shouldBe "en"
                promoter.descriptionAlt shouldBe "Die Hausagentur von Lido, Astra und Bi Nuu."
                promoter.descriptionAltLanguage shouldBe "de"
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

    // #1336: the review stamps a row; the weekly list is the rows without a stamp.
    @Test
    fun `GET promoters filters on whether a person reviewed the row`() {
        val reviewed = createPromoter(PromoterRequestFixtures.create(name = "Reviewed One", reviewedAt = Instant.parse("2026-09-11T18:00:00Z")))
        val minted = createPromoter(PromoterRequestFixtures.create(name = "Minted One"))

        fun slugsWhere(query: String): List<String> =
            webTestClient
                .get()
                .uri("/api/admin/promoters?$query")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody<PageResponse<PromoterResponse>>()
                .returnResult()
                .responseBody!!
                .content
                .map { it.slug }

        assertSoftly {
            slugsWhere("reviewed=false") shouldContainExactly listOf(minted.slug)
            slugsWhere("reviewed=true") shouldContainExactly listOf(reviewed.slug)
            slugsWhere("size=50") shouldContainExactlyInAnyOrder listOf(reviewed.slug, minted.slug)
            reviewed.reviewedAt shouldBe Instant.parse("2026-09-11T18:00:00Z")
            minted.reviewedAt shouldBe null
        }
    }

    // #2988: the admin's promoter picker finds a promoter by part of the name.
    @Test
    fun `GET promoters with name finds the names that contain it, ignoring case, alone and with the review filter`() {
        val concerts = createPromoter(PromoterRequestFixtures.create(name = "36 Concerts", reviewedAt = Instant.parse("2026-09-11T18:00:00Z")))
        val trinity = createPromoter(PromoterRequestFixtures.create(name = "Trinity Music Concerts"))
        val goodlive = createPromoter(PromoterRequestFixtures.create(name = "Goodlive"))

        fun search(query: String): PageResponse<PromoterResponse> =
            webTestClient
                .get()
                .uri("/api/admin/promoters?$query")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody<PageResponse<PromoterResponse>>()
                .returnResult()
                .responseBody!!

        assertSoftly {
            search("name=CONCERTS").content.map { it.id } shouldContainExactly listOf(concerts.id, trinity.id)
            search("name=concerts").totalElements shouldBe 2
            search("name=goodLIVE").content.map { it.id } shouldContainExactly listOf(goodlive.id)
            search("name=Bellmer").content shouldBe emptyList()
            search("name=Bellmer").totalElements shouldBe 0
            search("name=concerts&reviewed=true").content.map { it.id } shouldContainExactly listOf(concerts.id)
            search("name=concerts&reviewed=false").content.map { it.id } shouldContainExactly listOf(trinity.id)
            search("name=concerts&reviewed=false").totalElements shouldBe 1
        }
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
    fun `POST promoter with a description language outside de and en returns 400`() {
        webTestClient
            .post()
            .uri("/api/admin/promoters")
            .bodyValue(PromoterRequestFixtures.concerts36(descriptionLanguage = "fr"))
            .exchange()
            .expectStatus()
            .isBadRequest
    }

    @Test
    fun `POST promoter with an alternate text but no language returns 409`() {
        // The V024 CHECK refuses the half-marked row; the API maps the integrity violation to 409.
        webTestClient
            .post()
            .uri("/api/admin/promoters")
            .bodyValue(PromoterRequestFixtures.concerts36(descriptionAltLanguage = null))
            .exchange()
            .expectStatus()
            .isEqualTo(409)
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
