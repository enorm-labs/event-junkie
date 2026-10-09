package de.norm.events.venuecheck

import io.kotest.matchers.shouldBe
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class ForbiddenHostsTest {
    @ParameterizedTest(name = "{0} forbidden={1}")
    @CsvSource(
        "https://ra.co/clubs/12345, true",
        "https://de.ra.co/clubs/12345, true",
        "https://www.residentadvisor.net/club.aspx?id=1, true",
        "https://www.facebook.com/funkloch.berlin, true",
        "https://m.facebook.com/events/1, true",
        "https://fb.me/abc, true",
        "https://www.instagram.com/rso.berlin/, true",
        "https://www.eventbrite.com/o/venue-123, true",
        "https://www.eventbrite.de/e/party-tickets-1, true",
        "HTTPS://WWW.INSTAGRAM.COM./rso, true",
        "not a url, true",
        "https://funkloch.berlin/, false",
        "https://rso.berlin/, false",
        // A host that only contains a forbidden name is someone else's site.
        "https://notfacebook.com/, false",
        "https://extra.co/, false",
        "https://myeventbrite.com.example.org/, false"
    )
    fun `matches a forbidden host and its subdomains, nothing else`(
        url: String,
        forbidden: Boolean
    ) {
        ForbiddenHosts.isForbidden(url) shouldBe forbidden
    }
}
