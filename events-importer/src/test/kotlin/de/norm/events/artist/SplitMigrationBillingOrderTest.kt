package de.norm.events.artist

import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File

private val MIGRATION_DIR = File("src/main/resources/db/migration")

/** A split's acts inserted at the fused row's order plus their position. */
private val SPLIT_INSERT = Regex("""billing_order\s*\+\s*\w+\.position""", RegexOption.IGNORE_CASE)

/** The step that moves every later link down to make room for the extra acts. */
private val MAKE_ROOM = Regex("""SET\s+billing_order\s*=""", RegexOption.IGNORE_CASE)

/** Applied migrations that split without making room, each with the reason it stays. */
private val APPLIED_WITHOUT_ROOM: Map<String, String> =
    mapOf(
        "V222" to "every replacement row has position 0",
        "V223" to "every replacement row has position 0",
        "V224" to "its second acts can share an order with the next link (event 11839, Golden Gate)",
        "V244" to "its second act can share an order with the next link"
    )

/**
 * Asserts that a migration splitting one artist link into several acts moves the later links down first.
 *
 * Without that step the second act takes the order of the act billed after the fused row. A past event
 * is never imported again, so the duplicate order stays (V150 has the full shape).
 */
class SplitMigrationBillingOrderTest {
    @Test
    fun `a split migration makes room for its extra acts`() {
        val splits = splitMigrations()
        splits.shouldNotBeEmpty()
        splits
            .filterNot { (_, sql) -> MAKE_ROOM.containsMatchIn(sql) }
            .map { (version, _) -> version }
            .filterNot { it in APPLIED_WITHOUT_ROOM } shouldBe emptyList()
    }

    @Test
    fun `every exempt migration still splits without making room`() {
        val withoutRoom = splitMigrations().filterNot { (_, sql) -> MAKE_ROOM.containsMatchIn(sql) }.map { it.first }
        APPLIED_WITHOUT_ROOM.keys.filterNot { it in withoutRoom } shouldBe emptyList()
    }

    private fun splitMigrations(): List<Pair<String, String>> =
        MIGRATION_DIR
            .listFiles { file -> file.name.endsWith(".sql") }
            .orEmpty()
            .map { it.name.substringBefore("__") to it.readText() }
            .filter { (_, sql) -> SPLIT_INSERT.containsMatchIn(sql) }
}
