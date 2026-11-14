package de.norm.events.account

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** The pages a sign-in returns to (#3889, #4969). The frontend's `signInNext.spec.ts` holds the same cases. */
class SignInNextTest {
    @Test
    fun `a page of this site, alone or with the closed query`() {
        listOf(
            "/de/venues/sisyphos",
            "/en/events/tonight?do=rsvp-going",
            "/de/artists/ada?do=follow",
            "/en/events?genre=techno&genre=house&q=Caf%C3%A9+Nacht&from=2026-11-20&do=save"
        ).forEach { SignInNext.accepts(it) shouldBe true }
    }

    @Test
    fun `any other key, any other action, an absolute URL and a protocol-relative one are refused`() {
        listOf(
            "/de/events?next=//evil.example",
            "/de/events?genre=techno&utm_source=mail",
            "/de/events?do=unfollow",
            "/de/events?do=follow&do=delete-account",
            "/de/events?",
            "/de/events?genre=",
            "/de/events?genre=a b",
            "/de/events?genre=a#b",
            "https://evil.example/de/events?do=follow",
            "//evil.example/de/events?do=follow",
            "/fr/events?do=follow",
            "/de/events?do=follow\n"
        ).forEach { SignInNext.accepts(it) shouldBe false }
    }

    @Test
    fun `the query is capped at 1100 characters`() {
        val fits = "/de/events?q=" + "a".repeat(1098)
        SignInNext.accepts(fits) shouldBe true
        SignInNext.accepts(fits + "a") shouldBe false
    }
}
