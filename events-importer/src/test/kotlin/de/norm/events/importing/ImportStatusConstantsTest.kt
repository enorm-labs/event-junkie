package de.norm.events.importing

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Verifies that the [ImportStatus] companion `const val` strings stay in sync
 * with the enum names. These constants are used in `@Query` SQL annotations
 * where [ImportStatus.name] (a runtime property) cannot be used.
 */
class ImportStatusConstantsTest {
    @Test
    fun `S_IDLE matches IDLE name`() {
        ImportStatus.S_IDLE shouldBe ImportStatus.IDLE.name
    }

    @Test
    fun `S_RUNNING matches RUNNING name`() {
        ImportStatus.S_RUNNING shouldBe ImportStatus.RUNNING.name
    }

    @Test
    fun `S_SUCCESS matches SUCCESS name`() {
        ImportStatus.S_SUCCESS shouldBe ImportStatus.SUCCESS.name
    }

    @Test
    fun `S_FAILED matches FAILED name`() {
        ImportStatus.S_FAILED shouldBe ImportStatus.FAILED.name
    }

    @Test
    fun `S_MISCONFIGURED matches MISCONFIGURED name`() {
        ImportStatus.S_MISCONFIGURED shouldBe ImportStatus.MISCONFIGURED.name
    }

    @Test
    fun `every enum value has a corresponding constant`() {
        // If a new enum value is added without a matching constant, this test
        // forces the developer to add one (and a corresponding assertion above).
        val expectedConstants =
            setOf(
                ImportStatus.S_IDLE,
                ImportStatus.S_RUNNING,
                ImportStatus.S_SUCCESS,
                ImportStatus.S_FAILED,
                ImportStatus.S_MISCONFIGURED
            )
        ImportStatus.entries.map { it.name }.toSet() shouldBe expectedConstants
    }
}
