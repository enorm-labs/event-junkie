package de.norm.events

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.modulith.core.ApplicationModules

/**
 * Verifies the modular structure of the events-importer application.
 *
 * Spring Modulith treats each direct sub-package of the application's root package as an application module and validates that module boundaries are
 * respected (e.g. no cyclic dependencies, only public API types are accessed from the outside).
 *
 * @see <a href="https://docs.spring.io/spring-modulith/reference/">Spring Modulith Reference</a>
 */
class ModularityTests {
    private val modules = ApplicationModules.of(EventsImporterApplication::class.java)

    @Test
    fun `should have a valid modular structure`() {
        modules.verify()
    }

    @Test
    fun `should generate module documentation`() {
        // Writes Documenter output (component diagrams, module canvas) to
        // build/spring-modulith-docs so it can be reviewed or published.
        org.springframework.modulith.docs
            .Documenter(modules)
            .writeDocumentation()
    }

    /**
     * The picture of the same structure, in `docs/architecture/modules-events-importer.md` (#1721).
     *
     * The `Documenter` output above is PlantUML, which GitHub does not render, so it stays in `build/`.
     * This is the Mermaid one a reader sees, drawn from the modules themselves and asserted here.
     */
    @Test
    fun `the module diagram in docs matches the modules`() {
        val comparison = ModuleDiagram.compare(modules, "events-importer")

        withClue(
            "the diagram in ${comparison.document.name} no longer matches the modules. " +
                "Rewrite it with `./gradlew updateModuleDiagrams`."
        ) {
            comparison.actual shouldBe comparison.expected
        }
    }
}
