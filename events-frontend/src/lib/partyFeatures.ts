// The party features of a night (#2631), in display order — the same order and slugs as `PartyFeature`
// in the importer. Labels live in i18n under `partyFeature.<slug>`; an unknown slug renders as itself.

export const PARTY_FEATURES: readonly string[] = [
  'flinta-only',
  'queer',
  'sex-positive',
  'dress-code',
  'fetish-dress-code',
  'no-photo-policy',
  'open-end',
  'day-party',
]

/** The BFF orders features by slug; this puts them in display order, an unknown slug last. */
export function inFeatureOrder(slugs: readonly string[]): string[] {
  const rank = (slug: string) => {
    const index = PARTY_FEATURES.indexOf(slug)
    return index < 0 ? PARTY_FEATURES.length : index
  }
  return [...slugs].sort((a, b) => rank(a) - rank(b))
}
