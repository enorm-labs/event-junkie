package de.norm.events.search

import de.norm.events.artist.ArtistSummaryResponse
import de.norm.events.event.EventSummaryResponse
import de.norm.events.promoter.PromoterListItemResponse
import de.norm.events.venue.VenueListItemResponse
import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "The first matches of each kind, for the header search")
data class SearchResponse(
    @Schema(description = "Upcoming events, by date")
    val events: SearchGroup<EventSummaryResponse>,
    @Schema(description = "Venues, best match first")
    val venues: SearchGroup<VenueListItemResponse>,
    @Schema(description = "Artists, best match first")
    val artists: SearchGroup<ArtistSummaryResponse>,
    @Schema(description = "Promoters, best match first")
    val promoters: SearchGroup<PromoterListItemResponse>
)

@Schema(description = "The first matches of one kind, and how many there are in all")
data class SearchGroup<T>(
    @Schema(description = "At most `limit` items")
    val items: List<T>,
    @Schema(description = "Total number of matches of this kind", example = "12")
    val total: Long
)
