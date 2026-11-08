package de.norm.events.scraper

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class JsonExtensionsTest {
    private val post =
        SCRAPER_JSON.readTree(
            """
            {
              "title": {"rendered": " Rock &amp; Roll "},
              "excerpt": {"rendered": "  "},
              "content": {"protected": false},
              "slug": "rock-and-roll"
            }
            """.trimIndent()
        )

    @Test
    fun `reads a rendered field trimmed, with its entities still encoded`() {
        post.wpRendered("title") shouldBe "Rock &amp; Roll"
    }

    @Test
    fun `reads a blank, missing or plain field as absent`() {
        post.wpRendered("excerpt").shouldBeNull()
        post.wpRendered("content").shouldBeNull()
        post.wpRendered("slug").shouldBeNull()
        post.wpRendered("missing").shouldBeNull()
    }
}
