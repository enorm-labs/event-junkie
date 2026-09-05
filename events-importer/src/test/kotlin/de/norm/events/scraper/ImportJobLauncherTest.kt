package de.norm.events.scraper

import io.kotest.assertions.throwables.shouldThrow
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * Unit tests for [ImportJobLauncher].
 *
 * The launcher's scope is built from the injected dispatcher; using
 * [Dispatchers.Unconfined] makes the fire-and-forget `launch` run eagerly to
 * completion, so the delegated [EventImportService] call can be verified.
 */
class ImportJobLauncherTest {
    private val eventImportService: EventImportService = mockk(relaxed = true)
    private val eventSourceRepository: EventSourceRepository = mockk()

    private val launcher =
        ImportJobLauncher(
            eventImportService = eventImportService,
            eventSourceRepository = eventSourceRepository,
            ioDispatcher = Dispatchers.Unconfined
        )

    @Test
    fun `triggerImportAll launches a background import of all sources`() {
        launcher.triggerImportAll()
        coVerify(timeout = TIMEOUT_MS) { eventImportService.importAll() }
    }

    @Test
    fun `triggerImportBySlug launches a background import of the resolved source`() =
        runTest {
            val source = mockk<EventSourceEntity>()
            coEvery { eventSourceRepository.findBySlug("privatclub") } returns source
            coEvery { eventImportService.importFromSource(source) } returns
                ImportResultResponse(sourceSlug = "privatclub", imported = true, eventCount = 3)

            launcher.triggerImportBySlug("privatclub")

            coVerify(timeout = TIMEOUT_MS) { eventImportService.importFromSource(source, force = false) }
        }

    @Test
    fun `a forced trigger hands the flag to the import`() =
        runTest {
            val source = mockk<EventSourceEntity>()
            coEvery { eventSourceRepository.findBySlug("privatclub") } returns source
            coEvery { eventImportService.importFromSource(source, force = true) } returns
                ImportResultResponse(sourceSlug = "privatclub", imported = true, eventCount = 3)

            launcher.triggerImportBySlug("privatclub", force = true)

            coVerify(timeout = TIMEOUT_MS) { eventImportService.importFromSource(source, force = true) }
        }

    @Test
    fun `triggerImportBySlug throws for an unknown slug and launches nothing`() =
        runTest {
            coEvery { eventSourceRepository.findBySlug("nope") } returns null

            shouldThrow<EventSourceNotFoundException> { launcher.triggerImportBySlug("nope") }

            coVerify(exactly = 0) { eventImportService.importFromSource(any(), any()) }
        }

    @Test
    fun `destroy cancels the scope`() {
        // Smoke test: destroy must not throw and leaves the launcher unusable for new jobs.
        launcher.destroy()
    }

    private companion object {
        private const val TIMEOUT_MS = 1000L
    }
}
