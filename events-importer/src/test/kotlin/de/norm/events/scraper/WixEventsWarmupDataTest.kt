package de.norm.events.scraper

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test

class WixEventsWarmupDataTest {
    private fun page(warmup: String?) =
        Jsoup.parse(
            if (warmup == null) {
                "<html><body></body></html>"
            } else {
                """<html><body><script type="application/json" id="wix-warmup-data">$warmup</script></body></html>"""
            }
        )

    private fun registration(json: String) = SCRAPER_JSON.readTree(json)

    @Test
    fun `collectionRecords reads the named CMS collection's records`() {
        val records =
            WixEventsWarmupData.collectionRecords(
                page("""{"appsWarmupData": {"dataBinding": {"dataStore": {"recordsByCollectionId": {"Events": {"a1": {"title": "Night"}}}}}}}"""),
                "Events",
                EventSource.M_BIA
            )

        records
            .shouldNotBeNull()
            .path("a1")
            .path("title")
            .asString() shouldBe "Night"
    }

    @Test
    fun `collectionRecords is null without the script, for malformed JSON or another collection`() {
        WixEventsWarmupData.collectionRecords(page(null), "Events", EventSource.M_BIA).shouldBeNull()
        WixEventsWarmupData.collectionRecords(page("""{"appsWarmupData": """), "Events", EventSource.M_BIA).shouldBeNull()
        WixEventsWarmupData
            .collectionRecords(
                page("""{"appsWarmupData": {"dataBinding": {"dataStore": {"recordsByCollectionId": {"Other": {}}}}}}"""),
                "Events",
                EventSource.M_BIA
            ).shouldBeNull()
    }

    @Test
    fun `wixTicketing reads the ticketing block only when Wix sells the tickets`() {
        val soldByWix = registration("""{"type": $WIX_REGISTRATION_TICKETS, "ticketing": {"soldOut": true}}""")
        val external = registration("""{"type": 3, "ticketing": {"soldOut": true}}""")

        soldByWix.isSoldByWix() shouldBe true
        soldByWix
            .wixTicketing()
            .shouldNotBeNull()
            .path("soldOut")
            .asBoolean() shouldBe true
        external.isSoldByWix() shouldBe false
        external.wixTicketing().shouldBeNull()
    }

    @Test
    fun `isWixCheckoutOpen needs a Wix sale with an open registration`() {
        registration("""{"type": $WIX_REGISTRATION_TICKETS, "status": $WIX_REGISTRATION_OPEN_TICKETS}""").isWixCheckoutOpen() shouldBe true
        registration("""{"type": $WIX_REGISTRATION_TICKETS, "status": 6}""").isWixCheckoutOpen() shouldBe false
        registration("""{"type": 3, "status": $WIX_REGISTRATION_OPEN_TICKETS}""").isWixCheckoutOpen() shouldBe false
    }
}
