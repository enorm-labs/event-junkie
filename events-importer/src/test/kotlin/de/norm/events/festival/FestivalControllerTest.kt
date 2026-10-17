package de.norm.events.festival

import de.norm.events.BaseControllerTest
import de.norm.events.common.PageResponse
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.r2dbc.core.await
import org.springframework.test.web.reactive.server.expectBody
import java.time.LocalDate

class FestivalControllerTest : BaseControllerTest() {
    private fun request(
        name: String = "Fête de la Musique 2027",
        firstDay: LocalDate? = LocalDate.parse("2027-06-21"),
        lastDay: LocalDate? = LocalDate.parse("2027-06-21"),
        url: String = "https://www.fetedelamusique.de/"
    ) = FestivalRequest(
        name = name,
        url = url,
        firstDay = firstDay,
        lastDay = lastDay,
        noticeDe = "Musik an hunderten Orten der Stadt, draußen und kostenlos.",
        noticeEn = "Music at hundreds of places across the city, outdoors and free."
    )

    private fun create(request: FestivalRequest = request()): FestivalResponse =
        webTestClient
            .post()
            .uri("/api/admin/festivals")
            .bodyValue(request)
            .exchange()
            .expectStatus()
            .isCreated
            .expectBody<FestivalResponse>()
            .returnResult()
            .responseBody!!

    @Test
    fun `POST, GET, PUT, DELETE festival lifecycle`() {
        val created = create()
        assertSoftly {
            created.slug shouldBe "fete-de-la-musique-2027"
            created.firstDay shouldBe LocalDate.parse("2027-06-21")
            created.noticeDe shouldBe "Musik an hunderten Orten der Stadt, draußen und kostenlos."
            created.createdAt shouldNotBe null
        }

        webTestClient
            .get()
            .uri("/api/admin/festivals/${created.id}")
            .exchange()
            .expectStatus()
            .isOk
            .expectBody<FestivalResponse>()
            .returnResult()
            .responseBody!!
            .name shouldBe "Fête de la Musique 2027"

        val replaced =
            webTestClient
                .put()
                .uri("/api/admin/festivals/${created.id}")
                .bodyValue(request(name = "Tag der Clubkultur 2027", firstDay = LocalDate.parse("2027-10-02"), lastDay = LocalDate.parse("2027-10-10")))
                .exchange()
                .expectStatus()
                .isOk
                .expectBody<FestivalResponse>()
                .returnResult()
                .responseBody!!
        assertSoftly {
            replaced.id shouldBe created.id
            replaced.slug shouldBe "tag-der-clubkultur-2027"
            replaced.lastDay shouldBe LocalDate.parse("2027-10-10")
        }

        webTestClient
            .delete()
            .uri("/api/admin/festivals/${created.id}")
            .exchange()
            .expectStatus()
            .isNoContent
        webTestClient
            .get()
            .uri("/api/admin/festivals/${created.id}")
            .exchange()
            .expectStatus()
            .isNotFound
    }

    @Test
    fun `GET festivals lists the newest first`() {
        create(request(name = "CTM 2027", firstDay = LocalDate.parse("2027-01-22"), lastDay = LocalDate.parse("2027-01-31")))
        create(request(name = "Fête de la Musique 2027"))
        create(request(name = "Atonal 2027", firstDay = LocalDate.parse("2027-08-25"), lastDay = LocalDate.parse("2027-08-29")))

        val page =
            webTestClient
                .get()
                .uri("/api/admin/festivals")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody<PageResponse<FestivalResponse>>()
                .returnResult()
                .responseBody!!

        page.totalElements shouldBe 3
        page.content.map { it.slug } shouldContainExactly listOf("atonal-2027", "fete-de-la-musique-2027", "ctm-2027")
    }

    @Test
    fun `a name that folds to a taken slug is a 409, on create and on rename`() {
        create(request(name = "Fête de la Musique 2027"))
        val other = create(request(name = "CTM 2027"))

        for ((method, uri) in listOf("POST" to "/api/admin/festivals", "PUT" to "/api/admin/festivals/${other.id}")) {
            val spec = if (method == "POST") webTestClient.post() else webTestClient.put()
            spec
                .uri(uri)
                .bodyValue(request(name = "Fete de la Musique 2027"))
                .exchange()
                .expectStatus()
                .isEqualTo(409)
                .expectBody()
                .jsonPath("$.detail")
                .value<String> { it shouldContain "fete-de-la-musique-2027" }
        }
    }

    @Test
    fun `renaming to the same slug is not a conflict`() {
        val created = create(request(name = "CTM 2027"))

        webTestClient
            .put()
            .uri("/api/admin/festivals/${created.id}")
            .bodyValue(request(name = "ctm 2027"))
            .exchange()
            .expectStatus()
            .isOk
    }

    @Test
    fun `an unknown id is a 404 on get, put and delete`() {
        webTestClient
            .get()
            .uri("/api/admin/festivals/999")
            .exchange()
            .expectStatus()
            .isNotFound
        webTestClient
            .put()
            .uri("/api/admin/festivals/999")
            .bodyValue(request())
            .exchange()
            .expectStatus()
            .isNotFound
        webTestClient
            .delete()
            .uri("/api/admin/festivals/999")
            .exchange()
            .expectStatus()
            .isNotFound
    }

    @Test
    fun `a last day before the first day is a 400 that says why`() {
        webTestClient
            .post()
            .uri("/api/admin/festivals")
            .bodyValue(request(firstDay = LocalDate.parse("2027-06-21"), lastDay = LocalDate.parse("2027-06-20")))
            .exchange()
            .expectStatus()
            .isBadRequest
            .expectBody()
            .jsonPath("$.detail")
            .value<String> { it shouldContain "must not be before the first day" }
    }

    @Test
    fun `a missing day, a blank notice or a URL without a scheme is a 400`() {
        for (body in listOf(
            request(firstDay = null),
            request().copy(noticeEn = " "),
            request(url = "www.fetedelamusique.de")
        )) {
            webTestClient
                .post()
                .uri("/api/admin/festivals")
                .bodyValue(body)
                .exchange()
                .expectStatus()
                .isBadRequest
        }
    }

    @Test
    fun `the table refuses a last day before the first day`(): Unit =
        runBlocking {
            val error =
                shouldThrow<DataIntegrityViolationException> {
                    databaseClient
                        .sql(
                            "INSERT INTO events.festival (slug, name, url, first_day, last_day, notice_de, notice_en) " +
                                "VALUES ('x', 'X', 'https://x.example/', DATE '2027-06-21', DATE '2027-06-20', 'de', 'en')"
                        ).await()
                }
            error.message shouldContain "festival_days_ordered"
        }
}
