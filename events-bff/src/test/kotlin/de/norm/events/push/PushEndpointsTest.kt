package de.norm.events.push

import io.kotest.matchers.shouldBe
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/** Only the four push services: a subscription is a URL the BFF later posts to (ADR-055). */
class PushEndpointsTest {
    private val endpoints =
        PushEndpoints(
            listOf(
                "https://fcm.googleapis.com",
                "https://updates.push.services.mozilla.com",
                "https://web.push.apple.com",
                "https://*.notify.windows.com"
            )
        )

    @ParameterizedTest
    @CsvSource(
        "https://fcm.googleapis.com/fcm/send/abc:def, true",
        "https://updates.push.services.mozilla.com/wpush/v2/gAAA, true",
        "https://web.push.apple.com/QGuQyavXut, true",
        "https://wns2-par02p.notify.windows.com/w/?token=BQYAAA, true",
        "https://FCM.googleapis.com/fcm/send/x, true",
        "https://fcm.googleapis.com:443/fcm/send/x, true",
        "http://fcm.googleapis.com/fcm/send/x, false",
        "https://fcm.googleapis.com:8443/fcm/send/x, false",
        "https://fcm.googleapis.com.evil.example/x, false",
        "https://notify.windows.com/w/, false",
        "https://evilnotify.windows.com/w/, false",
        "https://user@fcm.googleapis.com/x, false",
        "https://10.0.0.1/x, false",
        "not a url, false"
    )
    fun `allows only the push services`(
        endpoint: String,
        allowed: Boolean
    ) {
        endpoints.isAllowed(endpoint) shouldBe allowed
    }
}
