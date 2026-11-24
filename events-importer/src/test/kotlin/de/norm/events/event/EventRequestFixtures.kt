package de.norm.events.event

import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import tools.jackson.module.kotlin.kotlinModule
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime

/**
 * Test fixture factory for [EventRequest] DTOs.
 *
 * ```kotlin
 * val request = EventRequestFixtures.adicts(venueId = 1)
 * val request = EventRequestFixtures.create(venueId = 1, sourceId = "test:event-1")
 * ```
 */
object EventRequestFixtures {
    /** Default event request modelled after The Adicts at Astra Kulturhaus. */
    fun adicts(
        venueId: Long,
        title: String = "THE ADICTS",
        subtitle: String? = "Adios Amigos Tour 2026",
        description: String? = null,
        eventType: EventType = EventType.CONCERT,
        status: EventStatus = EventStatus.SCHEDULED,
        eventDate: LocalDate = LocalDate.of(2026, 6, 12),
        doorsTime: LocalTime? = LocalTime.of(19, 0),
        startTime: LocalTime? = LocalTime.of(20, 0),
        imageUrl: String? = null,
        sourceUrl: String? = null,
        sourceId: String = "astra:2026-06-12-the-adicts",
        ticketUrl: String? = null,
        facebookEventUrl: String? = null,
        genre: String? = "Punk",
        pricePresale: BigDecimal? = BigDecimal("38.00"),
        priceBoxOffice: BigDecimal? = BigDecimal("45.00"),
        priceCurrency: String = "EUR",
        priceNote: String? = null,
        soldOut: Boolean = false,
        artists: List<EventArtistRequest> = emptyList(),
        promoterIds: List<Long> = emptyList()
    ): EventRequest =
        EventRequest(
            venueId = venueId,
            title = title,
            subtitle = subtitle,
            description = description,
            eventType = eventType,
            status = status,
            eventDate = eventDate,
            doorsTime = doorsTime,
            startTime = startTime,
            imageUrl = imageUrl,
            sourceUrl = sourceUrl,
            sourceId = sourceId,
            ticketUrl = ticketUrl,
            facebookEventUrl = facebookEventUrl,
            genre = genre,
            pricePresale = pricePresale,
            priceBoxOffice = priceBoxOffice,
            priceCurrency = priceCurrency,
            priceNote = priceNote,
            soldOut = soldOut,
            artists = artists,
            promoterIds = promoterIds
        )

    /** Creates an [EventRequest] with minimal defaults. */
    fun create(
        venueId: Long,
        title: String = "Test Event",
        sourceId: String,
        subtitle: String? = null,
        description: String? = null,
        eventType: EventType = EventType.CONCERT,
        status: EventStatus = EventStatus.SCHEDULED,
        eventDate: LocalDate = LocalDate.of(2026, 1, 1),
        doorsTime: LocalTime? = null,
        startTime: LocalTime? = null,
        imageUrl: String? = null,
        sourceUrl: String? = null,
        ticketUrl: String? = null,
        facebookEventUrl: String? = null,
        genre: String? = null,
        pricePresale: BigDecimal? = null,
        priceBoxOffice: BigDecimal? = null,
        priceCurrency: String = "EUR",
        priceNote: String? = null,
        soldOut: Boolean = false,
        artists: List<EventArtistRequest> = emptyList(),
        promoterIds: List<Long> = emptyList()
    ): EventRequest =
        EventRequest(
            venueId = venueId,
            title = title,
            subtitle = subtitle,
            description = description,
            eventType = eventType,
            status = status,
            eventDate = eventDate,
            doorsTime = doorsTime,
            startTime = startTime,
            imageUrl = imageUrl,
            sourceUrl = sourceUrl,
            sourceId = sourceId,
            ticketUrl = ticketUrl,
            facebookEventUrl = facebookEventUrl,
            genre = genre,
            pricePresale = pricePresale,
            priceBoxOffice = priceBoxOffice,
            priceCurrency = priceCurrency,
            priceNote = priceNote,
            soldOut = soldOut,
            artists = artists,
            promoterIds = promoterIds
        )
}

/**
 * The request as the wire carries it, so a test can tell a missing field from an explicit null.
 *
 * `put`, `putNull` and `remove` on the result send a field the DTO would not let a test leave out,
 * null out or misspell.
 */
fun EventRequest.asJsonObject(): ObjectNode = FIXTURE_MAPPER.valueToTree(this)

/** Field names straight onto the wire, for a test that sends a value no [EventRequest] property would hold. */
fun jsonBody(fields: Map<String, String?>): String = FIXTURE_MAPPER.writeValueAsString(fields)

private val FIXTURE_MAPPER = JsonMapper.builder().addModule(kotlinModule()).build()
