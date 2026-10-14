package de.norm.events.dataquality

import de.norm.events.BaseControllerTest
import de.norm.events.GlobalExceptionHandler
import de.norm.events.dataquality.classification.AnthropicEventClassifier
import de.norm.events.dataquality.classification.ClassificationRunResult
import de.norm.events.dataquality.classification.EventClassificationCheck
import de.norm.events.dataquality.classification.EventClassificationRepository
import de.norm.events.importing.EventSourceNotFoundException
import io.kotest.matchers.collections.shouldBeEmpty
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext
import org.springframework.test.web.reactive.server.WebTestClient

/**
 * The check routes. Off by default: no bean, no model client, and a 409 that says so (#3241).
 * The routes with the check on are driven against a mocked check, which costs no second context.
 */
class EventClassificationControllerTest : BaseControllerTest() {
    @Autowired
    private lateinit var context: ApplicationContext

    @Test
    fun `with the property off there is no check, no model client and no repository`() {
        context.getBeanNamesForType(EventClassificationCheck::class.java).toList().shouldBeEmpty()
        context.getBeanNamesForType(AnthropicEventClassifier::class.java).toList().shouldBeEmpty()
        context.getBeanNamesForType(EventClassificationRepository::class.java).toList().shouldBeEmpty()
    }

    @Test
    fun `with the property off both routes answer 409 and say why`() {
        listOf("/api/admin/data-quality/check/event/1", "/api/admin/data-quality/check/source/badehaus").forEach { uri ->
            webTestClient
                .post()
                .uri(uri)
                .exchange()
                .expectStatus()
                .isEqualTo(CONFLICT)
                .expectBody()
                .jsonPath("$.detail")
                .isEqualTo("The event-classification check is off: set app.data-quality.classification.enabled and a key")
        }
    }

    @Test
    fun `with the check on, a source run reports its counts`() {
        val check =
            mockk<EventClassificationCheck> {
                coEvery { checkSource("badehaus", true) } returns
                    ClassificationRunResult(
                        candidates = 4,
                        checked = 2,
                        unchanged = 1,
                        failed = 0,
                        overCap = 1,
                        suggested = sortedMapOf("startTime" to 2)
                    )
            }

        client(check)
            .post()
            .uri("/api/admin/data-quality/check/source/badehaus?force=true")
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.candidates")
            .isEqualTo(4)
            .jsonPath("$.checked")
            .isEqualTo(2)
            .jsonPath("$.overCap")
            .isEqualTo(1)
            .jsonPath("$.suggested.startTime")
            .isEqualTo(2)
    }

    @Test
    fun `with the check on, an unknown source is a 404`() {
        val check = mockk<EventClassificationCheck> { coEvery { checkSource("nowhere", false) } throws EventSourceNotFoundException("nowhere") }

        client(check)
            .post()
            .uri("/api/admin/data-quality/check/source/nowhere")
            .exchange()
            .expectStatus()
            .isNotFound
    }

    @Test
    fun `with the check on, one event is checked`() {
        val check =
            mockk<EventClassificationCheck> {
                coEvery { checkEvent(7, false) } returns ClassificationRunResult(1, 1, 0, 0, 0, emptyMap())
            }

        client(check)
            .post()
            .uri("/api/admin/data-quality/check/event/7")
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.checked")
            .isEqualTo(1)
    }

    private fun client(check: EventClassificationCheck): WebTestClient {
        val provider = mockk<ObjectProvider<EventClassificationCheck>> { every { ifAvailable } returns check }
        return WebTestClient
            .bindToController(EventClassificationController(provider))
            .controllerAdvice(GlobalExceptionHandler())
            .build()
    }

    private companion object {
        const val CONFLICT = 409
    }
}
