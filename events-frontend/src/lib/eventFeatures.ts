import type { EventFeature } from '@/api/types'

// The event features of a night (#2631), `for-children` (#3796) and `christmas-market` (#4972), in display order.
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
}

export const EVENT_FEATURES: readonly string[] = Object.keys(DISPLAY_ORDER)

/** The BFF orders features by slug; this puts them in display order, an unknown slug last. */
export function inFeatureOrder(slugs: readonly string[]): string[] {
  const rank = (slug: string) => {
    const index = EVENT_FEATURES.indexOf(slug)
    return index < 0 ? EVENT_FEATURES.length : index
  }
  return [...slugs].sort((a, b) => rank(a) - rank(b))
}
