package de.norm.events.artist

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern

/** The body of the narrow MBID write (#2946): the one field it changes. */
@Schema(description = "A MusicBrainz id a person chose for an artist")
data class MusicBrainzIdRequest(
    @field:NotNull(message = "MusicBrainz id is required")
    @field:Pattern(regexp = MBID_PATTERN, message = "MusicBrainz id must be a lowercase UUID")
    @Schema(
        description = "MusicBrainz artist id (MBID). It is stored as an EXACT match, which the sweep keeps",
        example = "06e3bce0-c612-4a5f-b095-9ffed1e4a656",
        requiredMode = Schema.RequiredMode.REQUIRED
    )
    val musicbrainzId: String?
)
