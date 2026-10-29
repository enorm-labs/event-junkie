package de.norm.events.docs

import de.norm.events.BaseControllerTest
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/**
 * Swagger UI is `testAndDevelopmentOnly`, so it serves under `bootRun` and here but never ships.
 * The OpenAPI document ships, because the DAST API scan reads it.
 */
class SwaggerUiWebjarTest : BaseControllerTest() {
    /**
     * springdoc resolves the webjar's *versioned* resource path, so a webjar version springdoc does
     * not expect is the change that could break the UI.
     */
    @Test
    fun `Swagger UI serves from the webjar`() {
        webTestClient
            .get()
            .uri("/webjars/swagger-ui/index.html")
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .consumeWith { it.responseBody?.decodeToString()?.shouldContain("swagger-ui") }
    }

    @Test
    fun `the OpenAPI document is generated`() {
        webTestClient
            .get()
            .uri("/v3/api-docs")
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.openapi")
            .exists()
    }
}
