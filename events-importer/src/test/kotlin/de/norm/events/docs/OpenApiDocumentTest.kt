package de.norm.events.docs

import de.norm.events.BaseControllerTest
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/** The importer's OpenAPI document names itself and describes every parameter an operator types (#349). */
class OpenApiDocumentTest : BaseControllerTest() {
    private fun document(): JsonNode {
        val body =
            webTestClient
                .get()
                .uri("/v3/api-docs")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody(String::class.java)
                .returnResult()
                .responseBody!!
        return JsonMapper().readTree(body)
    }

    @Test
    fun `the document carries the importer's title rather than SpringDoc's default`() {
        val info = document().path("info")

        info.path("title").asString() shouldBe "Events Importer"
        info.path("version").asString() shouldBe "v1"
    }

    @Test
    fun `every path and query parameter has a description`() {
        val described = mutableListOf<String>()
        val undescribed = mutableListOf<String>()
        for ((path, item) in document().path("paths").properties()) {
            for ((method, operation) in item.properties()) {
                for (parameter in operation.path("parameters").values()) {
                    val name = "${method.uppercase()} $path ${parameter.path("name").asString()}"
                    if (parameter.path("description").asString().isBlank()) undescribed += name else described += name
                }
            }
        }

        described.shouldNotBeEmpty()
        undescribed.shouldBeEmpty()
    }
}
