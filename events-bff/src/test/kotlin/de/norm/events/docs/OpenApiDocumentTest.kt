package de.norm.events.docs

import de.norm.events.BaseControllerTest
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Writes the BFF's OpenAPI document to the file `scripts/api-schema-parity.sh` generates the
 * frontend's types from (#370). The document comes from a booted context, so no running BFF is
 * needed; the path is a declared output of the test task, so a build-cache hit restores it.
 */
class OpenApiDocumentTest : BaseControllerTest() {
    @Test
    fun `the OpenAPI document is served and written for the schema check`() {
        val document =
            rootClient
                .get()
                .uri("/v3/api-docs")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody(String::class.java)
                .returnResult()
                .responseBody
                .shouldNotBeNull()

        withClue("the document names no event route — is springdoc scanning the controllers?") {
            document shouldContain "\"/api/events\""
        }

        val target = File(System.getProperty("openapi.document").shouldNotBeNull())
        target.parentFile.mkdirs()
        target.writeText(document)
    }
}
