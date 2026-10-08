package de.norm.events.importing

/**
 * Test fixture factory for [EventSourceCreateRequest] DTOs.
 *
 * Creates request objects with sensible defaults so tests only need to
 * specify the properties relevant to the scenario under test.
 *
 * ```kotlin
 * val request = EventSourceRequestFixtures.cassiopeia(venueId = 1L)
 * val request = EventSourceRequestFixtures.create(venueId = 1L, name = "Privatclub")
 * ```
 */
object EventSourceRequestFixtures {
    /** Default event source request modelled after the Cassiopeia venue importer. */
    fun cassiopeia(
        venueId: Long,
        name: String = "Cassiopeia Website",
        url: String = "https://www.cassiopeia-berlin.de/programm/",
        sourceType: String = "CASSIOPEIA",
        enabled: Boolean = true,
        importIntervalMinutes: Int = EventSourceEntity.DEFAULT_IMPORT_INTERVAL_MINUTES,
        maxRetries: Int = EventSourceEntity.DEFAULT_MAX_RETRIES
    ): EventSourceCreateRequest =
        EventSourceCreateRequest(
            venueId = venueId,
            name = name,
            url = url,
            sourceType = sourceType,
            enabled = enabled,
            importIntervalMinutes = importIntervalMinutes,
            maxRetries = maxRetries
        )

    /** Creates an [EventSourceCreateRequest] with minimal defaults. */
    fun create(
        venueId: Long,
        name: String = "Test Source",
        url: String = "https://example.com/events",
        sourceType: String = "CASSIOPEIA",
        enabled: Boolean = true,
        importIntervalMinutes: Int = EventSourceEntity.DEFAULT_IMPORT_INTERVAL_MINUTES,
        maxRetries: Int = EventSourceEntity.DEFAULT_MAX_RETRIES
    ): EventSourceCreateRequest =
        EventSourceCreateRequest(
            venueId = venueId,
            name = name,
            url = url,
            sourceType = sourceType,
            enabled = enabled,
            importIntervalMinutes = importIntervalMinutes,
            maxRetries = maxRetries
        )
}
