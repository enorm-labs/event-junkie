import type { EventFeature } from '@/api/types'
import { CHRISTMAS_MARKET } from './christmasMarkets'

// The event features of a night (#2631), `for-children` (#3796), `christmas-market` (#4972) and a market's kinds (#5007),
// in display order.
// A Record so the compiler rejects a missing or unknown slug against the generated union, which comes from
// `EventFeature` in events-core. Labels live in i18n under `eventFeature.<slug>`; an unknown slug renders as itself.

const DISPLAY_ORDER: Record<EventFeature, true> = {
  'flinta-only': true,
  queer: true,
  'sex-positive': true,
  'dress-code': true,
  'fetish-dress-code': true,
  'no-photo-policy': true,
  'open-end': true,
  'day-party': true,
  'open-stage': true,
  'for-children': true,
  'christmas-market': true,
  'christmas-market-traditional': true,
  'christmas-market-historic': true,
  'christmas-market-craft': true,
  'christmas-market-funfair': true,
  'christmas-market-kiez': true,
  'christmas-market-alternative': true,
}

export const EVENT_FEATURES: readonly string[] = Object.keys(DISPLAY_ORDER)

/** A children's show (EVENT_SCOPE.md §3.5): hidden from the default lists, listed on its own page (#5079). */
export const FOR_CHILDREN = 'for-children'

/** A Christmas market's kind is the feature `christmas-market-<kind>`; only the markets page filters by it. */
export const MARKET_KIND_PREFIX = 'christmas-market-'

/** The kinds of market, as the `kind` the markets page carries in its URL. */
export const MARKET_KINDS: readonly string[] = EVENT_FEATURES.filter((slug) =>
  slug.startsWith(MARKET_KIND_PREFIX),
).map((slug) => slug.slice(MARKET_KIND_PREFIX.length))

/** The features the events list filters by: no market and no kind, which the markets page alone shows (#5019). */
export const FILTER_FEATURES: readonly string[] = EVENT_FEATURES.filter(
  (slug) => slug !== CHRISTMAS_MARKET && !slug.startsWith(MARKET_KIND_PREFIX),
)

/** The BFF orders features by slug; this puts them in display order, an unknown slug last. */
export function inFeatureOrder(slugs: readonly string[]): string[] {
  const rank = (slug: string) => {
    const index = EVENT_FEATURES.indexOf(slug)
    return index < 0 ? EVENT_FEATURES.length : index
  }
  return [...slugs].sort((a, b) => rank(a) - rank(b))
}
