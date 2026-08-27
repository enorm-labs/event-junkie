package de.norm.events.scraper

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File

private val COMMITTED_TABLE = File("../docs/data-quality/ACCEPTED_LIMITATIONS.md")
private val REGENERATED_TABLE = File("build/accepted-limitations.md")

class AcceptedLimitationsTest {
    @Test
    fun `every event source is declared exactly once`() {
        val declared = AcceptedLimitations.declarations.flatMap { it.sources }
        declared
            .groupBy { it }
            .filterValues { it.size > 1 }
            .keys
            .shouldBeEmpty()
        (EventSource.entries - declared.toSet()).shouldBeEmpty()
    }

    @Test
    fun `a limitation only names a source its own declaration serves`() {
        AcceptedLimitations.declarations
            .filter { it.limitations.isNotEmpty() && it.sources.isEmpty() }
            .shouldBeEmpty()
    }

    @Test
    fun `no source declares the same aspect twice`() {
        val duplicates =
            EventSource.entries.flatMap { source ->
                AcceptedLimitations
                    .forSource(source)
                    .groupBy { it.aspect }
                    .filterValues { it.size > 1 }
                    .keys
                    .map { "$source/$it" }
            }
        duplicates.shouldBeEmpty()
    }

    // The reason completes "the field is absent because …", so a capital or a full stop means someone
    // wrote a sentence about the parser instead of a fact about the source.
    @Test
    fun `every reason reads as a property of the source`() {
        val malformed =
            AcceptedLimitations.declarations
                .flatMap { it.limitations }
                .filter { it.reason.isBlank() || it.reason.first().isUpperCase() || it.reason.endsWith(".") }
                .map { it.reason }
        malformed.shouldBeEmpty()
    }

    /**
     * A coverage series with no aspect is a series the audit cannot explain: it would read 0% for a
     * venue forever with nothing anywhere saying why that is fine.
     */
    @Test
    fun `every tracked field can be declared`() {
        val reachable = LimitedAspect.entries.mapNotNull { it.trackedField }.toSet()
        (TrackedField.entries - reachable).shouldBeEmpty()
    }

    /**
     * The audit reads the rendered table, not the Kotlin, so the two have to agree. A doc nothing
     * checks drifts, and this is the check.
     *
     * Compared after [normalize], because `scripts/format-markdown.sh` pads the table's columns to
     * align them and the renderer does not. Padding is the formatter's business; content is this
     * test's.
     */
    @Test
    fun `the committed table matches the declarations`() {
        val rendered = renderMarkdown()
        if (normalize(COMMITTED_TABLE.readText()) != normalize(rendered)) {
            REGENERATED_TABLE.parentFile.mkdirs()
            REGENERATED_TABLE.writeText(rendered)
            error(
                "${COMMITTED_TABLE.path} is stale. Regenerate it:\n" +
                    "  cp events-importer/${REGENERATED_TABLE.path} ${COMMITTED_TABLE.path.removePrefix("../")}\n" +
                    "  scripts/format-markdown.sh"
            )
        }
        normalize(COMMITTED_TABLE.readText()) shouldBe normalize(rendered)
    }
}

/** Table content without the column padding `scripts/format-markdown.sh` adds. */
private fun normalize(markdown: String): String =
    markdown
        .lines()
        .joinToString("\n") { line ->
            if (!line.startsWith("|")) line else line.split("|").joinToString("|") { it.trim().replace(Regex("^-{3,}$"), "---") }
        }.trim()

private fun renderMarkdown(): String {
    val rows =
        EventSource.entries
            .flatMap { source -> AcceptedLimitations.forSource(source).map { source to it } }
            .map { (source, limitation) ->
                val issue = limitation.issue?.let { "#$it" } ?: "—"
                "| `$source` | `${limitation.aspect}` | ${limitation.reason} | $issue |"
            }
    val silent = EventSource.entries.filter { AcceptedLimitations.forSource(it).isEmpty() }
    val header =
        listOf(
            "# Accepted limitations",
            "",
            "<!-- Generated from AcceptedLimitations.kt by AcceptedLimitationsTest. Do not edit by hand. -->",
            "",
            "What each venue's source does not publish, declared next to its parser (#715). A data-quality finding matching a row here is",
            "**known and accepted**, not a defect — see [`/data-quality-audit`](../../.github/prompts/data-quality-audit.prompt.md).",
            "",
            "A declaration says the source is silent, not that the column is always null: where the parser derives a value anyway, the reason",
            "says so.",
            "",
            "| Source | Aspect | Why the source is silent | Issue |",
            "| --- | --- | --- | --- |"
        )
    val footer =
        listOf(
            "",
            "## Sources with nothing declared",
            "",
            "These publish everything the model stores, as of the last review:",
            "",
            silent.joinToString(", ") { "`$it`" }
        )
    return (header + rows + footer).joinToString("\n") + "\n"
}
