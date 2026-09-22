package de.norm.events

import org.springframework.modulith.core.ApplicationModules
import java.io.File

/**
 * The module diagram in `docs/architecture/modules-<application>.md`, from the modules themselves (#1721).
 *
 * Spring Modulith's own `Documenter` writes PlantUML, which GitHub does not render, so its output stays
 * in `build/` and this draws Mermaid from `ApplicationModules` instead.
 *
 * **One document per application, never one with three blocks.** CI runs `./gradlew test --parallel`, so
 * the three modules' test tasks write at the same time, and the loser of that race would win the file.
 *
 * It lives in test fixtures because all three modules need it, and each can only see its own
 * application class. Each module's `ModularityTests` compares, and
 * `./gradlew updateModuleDiagrams` rewrites.
 */
object ModuleDiagram {
    const val UPDATE_PROPERTY = "updateModuleDiagram"

    private const val START = "<!-- generated: module diagram -->"
    private const val END = "<!-- /generated: module diagram -->"

    /**
     * What the document holds against what the modules say, for one application.
     *
     * Rewrites the document instead, when `-DupdateModuleDiagram=true` is set, which is what the Gradle
     * task passes. The comparison is then trivially equal, so one code path serves both.
     */
    fun compare(
        modules: ApplicationModules,
        application: String
    ): Comparison {
        val document = File(repositoryRoot(), "docs/architecture/modules-$application.md")
        val expected = "$START\n\n```mermaid\n${render(modules)}```\n\n$END"

        if (System.getProperty(UPDATE_PROPERTY) == "true") {
            write(document, application, expected)
        }

        return Comparison(document, expected, block(document))
    }

    class Comparison(
        val document: File,
        val expected: String,
        val actual: String
    )

    /**
     * A node per module and an edge per **actual** direct dependency.
     *
     * Actual, not the declared `allowedDependencies`: `ModularityTests.verify()` already fails when the
     * two disagree, so the narrower of the two is both true and the more useful. A module declared
     * `Type.OPEN` carries the marker, because an open module is the one exception to the boundary the
     * rest of the picture draws.
     */
    fun render(modules: ApplicationModules): String {
        val sorted = modules.sortedBy { it.identifier.toString() }
        val nodes =
            sorted.joinToString("\n") { module ->
                val open = if (module.isOpen) " «open»" else ""
                """    ${module.identifier}["${module.identifier}$open"]"""
            }
        val edges =
            sorted
                .flatMap { module ->
                    module
                        .getDirectDependencies(modules)
                        .uniqueModules()
                        .map { "    ${module.identifier} --> ${it.identifier}" }
                        .toList()
                }.distinct()
                .sorted()

        val body = if (edges.isEmpty()) "" else "\n\n" + edges.joinToString("\n")
        return "flowchart TD\n$nodes$body\n"
    }

    private fun block(document: File): String {
        if (!document.isFile) return "<the document does not exist>"
        val text = document.readText()
        val start = text.indexOf(START)
        val end = text.indexOf(END)
        return if (start < 0 || end <= start) "<no $START … $END markers>" else text.substring(start, end + END.length)
    }

    private fun write(
        document: File,
        application: String,
        expected: String
    ) {
        document.parentFile.mkdirs()
        val text = if (document.isFile) document.readText() else heading(application)
        val start = text.indexOf(START)
        val end = text.indexOf(END)
        document.writeText(
            if (start < 0 || end <= start) {
                text + expected + "\n"
            } else {
                text.substring(0, start) + expected + text.substring(end + END.length)
            }
        )
    }

    private fun heading(application: String) =
        """
        # Modules — $application

        The application modules and the dependencies between them, as Spring Modulith reads them.

        **This diagram is generated.** Do not edit it by hand. Rewrite it with `./gradlew updateModuleDiagrams`.

        """.trimIndent() + "\n"

    private fun repositoryRoot(): File {
        val root = File("..").absoluteFile.normalize()
        check(File(root, "settings.gradle.kts").isFile) {
            "expected the repository root at $root — this writes a document outside the module it runs in"
        }
        return root
    }
}
