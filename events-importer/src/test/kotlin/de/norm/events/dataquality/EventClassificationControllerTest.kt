package de.norm.events.dataquality

import de.norm.events.BaseControllerTest
import de.norm.events.GlobalExceptionHandler
import de.norm.events.dataquality.classification.AnthropicEventClassifier
import de.norm.events.dataquality.classification.ClassificationProperties
import de.norm.events.dataquality.classification.ClassificationRunResult
import de.norm.events.dataquality.classification.ClassificationRunner
import de.norm.events.dataquality.classification.ClassificationTokenLedger
import de.norm.events.dataquality.classification.EventClassificationCheck
import de.norm.events.dataquality.classification.EventClassificationRepository
import de.norm.events.importing.EventSourceEntity
import de.norm.events.importing.EventSourceRepository
import io.kotest.matchers.collections.shouldBeEmpty
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext
import org.springframework.test.web.reactive.server.WebTestClient
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The check routes. Off by default: no bean, no model client, and a 409 that says so (#3241).
 * The routes with the check on are driven against a mocked check and a real runner, which costs no second context.
 */
class EventClassificationControllerTest : BaseControllerTest() {
    @Autowired
    private lateinit var context: ApplicationContext

    @Test
    fun `with the property off there is no check, no model client, no repository and no nightly pass`() {
        context.getBeanNamesForType(EventClassificationCheck::class.java).toList().shouldBeEmpty()
        context.getBeanNamesForType(AnthropicEventClassifier::class.java).toList().shouldBeEmpty()
        context.getBeanNamesForType(EventClassificationRepository::class.java).toList().shouldBeEmpty()
        context.getBeanNamesForType(ClassificationRunner::class.java).toList().shouldBeEmpty()
        context.getBeanNamesForType(ClassificationTokenLedger::class.java).toList().shouldBeEmpty()
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
    fun `with the check on, a source run answers 202 at once, and 409 while it is going`() {
        val gate = CompletableDeferred<Unit>()
        val check =
            mockk<EventClassificationCheck> {
                coEvery { checkSource("badehaus", true, any()) } coAnswers {
                    gate.await()
                    ClassificationRunResult(1, 1, 0, 0, 0, emptyMap())
                }
            }
        val runner = runner(check)
        val client = client(mockk(), runner)

        client
            .post()
            .uri("/api/admin/data-quality/check/source/badehaus?force=true")
            .exchange()
            .expectStatus()
            .isAccepted
            .expectBody()
            .jsonPath("$.sourceSlug")
            .isEqualTo("badehaus")

        client
            .post()
            .uri("/api/admin/data-quality/check/source/badehaus?force=true")
            .exchange()
            .expectStatus()
            .isEqualTo(CONFLICT)
            .expectBody()
            .jsonPath("$.detail")
            .isEqualTo("An event-classification run is going. Start another one when it has finished")

        gate.complete(Unit)
        runBlocking { withTimeout(WAIT) { while (runner.runNightly() == null) delay(POLL) } }
        coVerify(exactly = 1) { check.checkSource("badehaus", true, any()) }
    }

    @Test
    fun `with the check on, an unknown source is a 404 and starts nothing`() {
        val check = mockk<EventClassificationCheck>()
        val sources = mockk<EventSourceRepository> { coEvery { findBySlug("nowhere") } returns null }

        client(mockk(), runner(check, sources))
            .post()
            .uri("/api/admin/data-quality/check/source/nowhere")
            .exchange()
            .expectStatus()
            .isNotFound
        coVerify(exactly = 0) { check.checkSource(any(), any(), any()) }
    }

    @Test
    fun `with the check on, one event is checked`() {
        val check =
            mockk<EventClassificationCheck> {
                coEvery { checkEvent(7, false) } returns ClassificationRunResult(1, 1, 0, 0, 0, emptyMap())
            }

        client(check, mockk())
            .post()
            .uri("/api/admin/data-quality/check/event/7")
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.checked")
            .isEqualTo(1)
            .jsonPath("$.overBudget")
            .isEqualTo(0)
    }

    /** A real runner around a mocked check, so the lock and the background launch are the ones under test. */
    private fun runner(
        check: EventClassificationCheck,
        sources: EventSourceRepository = mockk { coEvery { findBySlug(any()) } returns mockk<EventSourceEntity>() }
    ): ClassificationRunner {
        val repository = mockk<EventClassificationRepository> { coEvery { countSourceCandidates() } returns emptyMap() }
        val ledger =
            mockk<ClassificationTokenLedger> {
                coEvery { monthTotal() } returns 0
                every { budget } returns BUDGET
            }
        return ClassificationRunner(
            check,
            repository,
            sources,
            DataQualityMetrics(SimpleMeterRegistry()),
            ledger,
            ClassificationProperties(enabled = true),
            Dispatchers.IO
        )
    }

    private fun client(
        check: EventClassificationCheck,
        runner: ClassificationRunner
    ): WebTestClient {
        val checkProvider = mockk<ObjectProvider<EventClassificationCheck>> { every { ifAvailable } returns check }
        val runnerProvider = mockk<ObjectProvider<ClassificationRunner>> { every { ifAvailable } returns runner }
        return WebTestClient
            .bindToController(EventClassificationController(checkProvider, runnerProvider))
            .controllerAdvice(GlobalExceptionHandler())
            .build()
    }

    private companion object {
        const val CONFLICT = 409
        const val BUDGET = 1_000L
        val WAIT = 10.seconds
        val POLL = 20.milliseconds
    }
}
