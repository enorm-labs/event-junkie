// The event features of a night (#2631) and `for-children` (#3796), in display order — the same order and slugs as `EventFeature`
// in the importer. Labels live in i18n under `eventFeature.<slug>`; an unknown slug renders as itself.

export const EVENT_FEATURES: readonly string[] = [
  'flinta-only',
  'queer',
  'sex-positive',
  'dress-code',
  'fetish-dress-code',
  'no-photo-policy',
  'open-end',
  'day-party',
  'open-stage',
  'for-children',
]

/** The BFF orders features by slug; this puts them in display order, an unknown slug last. */
export function inFeatureOrder(slugs: readonly string[]): string[] {
  const rank = (slug: string) => {
    const index = EVENT_FEATURES.indexOf(slug)
    return index < 0 ? EVENT_FEATURES.length : index
  }
  return [...slugs].sort((a, b) => rank(a) - rank(b))
}
