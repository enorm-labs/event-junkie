package de.norm.events

import de.norm.events.artist.Artist
import de.norm.events.event.Event
import de.norm.events.event.LineupEntry
import de.norm.events.genretag.GenreTag
import de.norm.events.promoter.Promoter
import de.norm.events.venue.Venue
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import java.io.File
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.full.primaryConstructor
import kotlin.test.Test

/**
 * The class diagram in `docs/DATA_MODEL.md` is generated from the classes it draws (#394).
 *
 * It was hand-drawn until this test, and it had drifted: eight fields missing on [Venue], seventeen
 * on [Artist], and a `relocatedTo` that exists on both applications' `EventEntity` and on no domain
 * class. A wrong diagram is worse than none, because it is believed.
 *
 * **The source is `events-core`'s domain classes, not the entities and not the migrations.** ADR-003
 * makes these the shared model and each `*Entity` a per-application detail — there are two
 * `EventEntity` classes and they differ, so "the entities" would mean picking one application. The
 * database is documented below the diagram, in the field tables, which carry prose nothing
 * generates. Where the two disagree, the tables win: the column is what exists.
 *
 * Rewrite the diagram with `./gradlew :events-core:updateDataModelDiagram`.
 */
class DomainClassDiagramTest {
    @Test
    fun `the diagram in DATA_MODEL matches the domain classes`() {
        val file = dataModel()
        val text = file.readText()
        val start = text.indexOf(START_MARKER)
        val end = text.indexOf(END_MARKER)

        withClue("expected $START_MARKER and $END_MARKER around the diagram in ${file.name}") {
            (start >= 0 && end > start) shouldBe true
        }

        val expected = START_MARKER + "\n\n```mermaid\n" + diagram() + "```\n\n" + END_MARKER
        val actual = text.substring(start, end + END_MARKER.length)

        if (actual != expected && System.getProperty(UPDATE_PROPERTY) == "true") {
            file.writeText(text.substring(0, start) + expected + text.substring(end + END_MARKER.length))
            return
        }

        withClue(
            "the diagram in ${file.name} no longer matches the domain classes. Rewrite it with " +
                "`./gradlew :events-core:updateDataModelDiagram`, then read the field tables below it: a new " +
                "field here usually means a new column there, and nothing generates those."
        ) {
            actual shouldBe expected
        }
    }

    /**
     * The whole `classDiagram` body, from the classes and nothing else.
     *
     * A property whose type is another domain class is drawn as an edge rather than a member, which
     * is how the diagram read when a person maintained it. Everything else is a member line.
     */
    private fun diagram(): String {
        val enums = LinkedHashSet<KClass<*>>()
        val edges = mutableListOf<String>()
        val classes = StringBuilder()

        DOMAIN_CLASSES.forEach { type ->
            classes.append("    class ${type.simpleName} {\n")
            type.primaryConstructor!!.parameters.forEach { parameter ->
                val name = parameter.name!!
                val target = parameter.type.domainTarget()
                when {
                    target != null && parameter.type.isList() -> {
                        edges += "    ${type.simpleName} \"1\" --> \"*\" ${target.simpleName}: $name"
                    }

                    target != null -> {
                        edges += "    ${type.simpleName} \"*\" --> \"1\" ${target.simpleName}: $name"
                    }

                    else -> {
                        val classifier = parameter.type.classifier as KClass<*>
                        if (classifier.java.isEnum) {
                            enums += classifier
                            edges += "    ${type.simpleName} --> ${classifier.simpleName}: $name"
                        }
                        classes.append("        ${parameter.type.render()} $name\n")
                    }
                }
            }
            classes.append("    }\n\n")
        }

        enums.forEach { type ->
            classes.append("    class ${type.simpleName} {\n        <<enumeration>>\n")
            type.java.enumConstants.forEach { classes.append("        $it\n") }
            classes.append("    }\n\n")
        }

        return "classDiagram\n    direction LR\n\n$classes${edges.joinToString("\n")}\n"
    }

    /** `List<Artist>` and `Artist` both answer [Artist]; `String` and `EventType` answer null. */
    private fun KType.domainTarget(): KClass<*>? =
        when (val type = classifier as? KClass<*>) {
            in DOMAIN_CLASSES -> type
            List::class -> (arguments.single().type?.classifier as? KClass<*>)?.takeIf { it in DOMAIN_CLASSES }
            else -> null
        }

    private fun KType.isList(): Boolean = classifier == List::class

    /**
     * A nullable type keeps its `?`. Mermaid treats a member line as text, so the marker survives,
     * and dropping it would make every optional field read as required.
     */
    private fun KType.render(): String = (classifier as KClass<*>).simpleName + if (isMarkedNullable) "?" else ""

    private fun dataModel(): File {
        val root = File("..").absoluteFile.normalize()
        withClue("expected the repository root at $root — this test rewrites a file outside its own module") {
            File(root, "settings.gradle.kts").isFile shouldBe true
        }
        val file = File(root, "docs/DATA_MODEL.md")
        withClue("expected the document at $file") { file.isFile shouldBe true }
        return file
    }

    private companion object {
        /**
         * Listed, not scanned. A classpath scan would pull in whatever a later module adds, and the
         * build would fail with what looks like a diagram defect rather than a decision to make.
         * The order is the order the classes are drawn in.
         */
        val DOMAIN_CLASSES =
            listOf(Venue::class, Event::class, LineupEntry::class, Artist::class, Promoter::class, GenreTag::class)

        const val START_MARKER = "<!-- generated: domain class diagram -->"
        const val END_MARKER = "<!-- /generated: domain class diagram -->"
        const val UPDATE_PROPERTY = "updateDataModelDiagram"
    }
}
