import type { ArtistDetail, EventDetail, PromoterDetail, VenueDetail } from '../src/api/types.ts'
import type { Locale } from '../src/i18n/locales.ts'
import {
  artistPageMeta,
  eventPageMeta,
  type PageMeta,
  promoterPageMeta,
  venuePageMeta,
} from '../src/lib/pageMeta.ts'
import { eventPageJsonLd, type JsonLd, venuePageJsonLd } from '../src/lib/structuredData.ts'
import type { EntityKind } from './routes.ts'

/**
 * The per-kind glue between a route and `lib/pageMeta.ts`: where the entity lives on the BFF, and
 * which builders turn it into head tags and structured data.
 *
 * Nothing here composes a title or a description. That is the whole point of ADR-014 §Decision 3 —
 * the client and the injector must produce identical values, and the only way to hold that is for
 * neither of them to have an opinion of its own.
 */

/** The image dimensions the BFF knows, for `og:image:width`/`height`, when it knows them. */
export interface ImageSize {
  width: number
  height: number
}

export interface EntityMeta {
  meta: PageMeta
  image?: ImageSize
  /** Only the kinds whose view publishes structured data: events and venues. */
  structuredData?: JsonLd[]
}

/** The BFF path for an entity. The slug has already passed the route regex, so it needs no escaping. */
export function bffPath(kind: EntityKind, slug: string): string {
  return `/api/${kind}/${slug}`
}

interface SizedEntity {
  intrinsicWidth?: number | null
  intrinsicHeight?: number | null
}

function imageSize(entity: SizedEntity): ImageSize | undefined {
  const { intrinsicWidth: width, intrinsicHeight: height } = entity
  return width && height ? { width, height } : undefined
}

/** Applies the builder for `kind` to whatever the BFF returned. */
export function entityMeta(kind: EntityKind, entity: unknown, locale: Locale): EntityMeta {
  switch (kind) {
    case 'events': {
      const event = entity as EventDetail
      return {
        meta: eventPageMeta(event, locale),
        image: imageSize(event),
        structuredData: eventPageJsonLd(event, locale),
      }
    }
    case 'venues': {
      const venue = entity as VenueDetail
      return {
        meta: venuePageMeta(venue, locale),
        image: imageSize(venue),
        structuredData: venuePageJsonLd(venue, locale),
      }
    }
    case 'artists': {
      const artist = entity as ArtistDetail
      return { meta: artistPageMeta(artist), image: imageSize(artist) }
    }
    case 'promoters': {
      const promoter = entity as PromoterDetail
      return { meta: promoterPageMeta(promoter, locale), image: imageSize(promoter) }
    }
  }
}
