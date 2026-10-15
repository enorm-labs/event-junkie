package de.norm.events.instagram

import io.kotest.matchers.shouldBe
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class InstagramHandleTest {
    @ParameterizedTest
    @CsvSource(
        nullValues = ["null"],
        value = [
            "https://www.instagram.com/rso.berlin/, rso.berlin",
            "https://instagram.com/prisma.berlin, prisma.berlin",
            "https://www.instagram.com/momslimousineservice/?hl=de, momslimousineservice",
            "https://www.instagram.com/p/DecMBi9gqGk/, null",
            "https://www.instagram.com/reel/DePNNkDuZIq/, null",
            "https://www.facebook.com/rso.berlin, null",
            "null, null"
        ]
    )
    fun `the handle of a profile URL, and none for anything else`(
        url: String?,
        handle: String?
    ) {
        InstagramHandle.of(url) shouldBe handle
    }
}
