package de.norm.events.instagram

import de.norm.events.BaseControllerTest
import org.junit.jupiter.api.Test

/** The suite runs with app.instagram.enabled=false, so both routes answer the 409 that tells "off" from a typo. */
class InstagramControllerTest : BaseControllerTest() {
    @Test
    fun `reading a venue while the drafts are off is a 409`() {
        webTestClient
            .post()
            .uri("/api/admin/instagram/venues/rso-berlin/read")
            .exchange()
            .expectStatus()
            .isEqualTo(409)
            .expectBody()
            .jsonPath("$.detail")
            .isEqualTo("Instagram drafts are off: set app.instagram.enabled, a token and a key")
    }

    @Test
    fun `starting a pass while the drafts are off is a 409`() {
        webTestClient
            .post()
            .uri("/api/admin/instagram/run")
            .exchange()
            .expectStatus()
            .isEqualTo(409)
    }
}
