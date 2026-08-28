import type { components } from './schema'

// Friendly aliases for the generated BFF response schemas, so views and composables don't
// reach into `components['schemas'][...]` directly. Note: every field is optional because the
// BFF's OpenAPI spec does not emit `required` metadata — guard with optional chaining/defaults.
type Schemas = components['schemas']

export type EventSummary = Schemas['EventSummaryResponse']
export type EventDetail = Schemas['EventDetailResponse']
export type LineupEntry = Schemas['LineupEntryResponse']
export type VenueSummary = Schemas['VenueSummaryResponse']
export type VenueDetail = Schemas['VenueDetailResponse']
export type ArtistSummary = Schemas['ArtistSummaryResponse']
export type ArtistDetail = Schemas['ArtistDetailResponse']
export type PromoterSummary = Schemas['PromoterSummaryResponse']
export type PromoterDetail = Schemas['PromoterDetailResponse']
export type GenreTag = Schemas['GenreTagResponse']
export type ImageSource = Schemas['ImageSourceResponse']
export type EventPage = Schemas['PageResponseEventSummaryResponse']
export type VenuePage = Schemas['PageResponseVenueSummaryResponse']
export type AppMeta = Schemas['MetaResponse']
