import type { PromoterDetail } from '@/api/types'

type PromoterKind = NonNullable<PromoterDetail['kind']>

// Only the kinds a visitor follows a presenter for get a word; "promoter", "media" and the rest say nothing new (#5080).
const SHOWN_KINDS: readonly PromoterKind[] = ['collective', 'label', 'party']

/** The i18n key that names [kind] on a promoter page or card, or null for a kind that shows nothing. */
export function promoterKindKey(kind: PromoterDetail['kind'] | undefined): string | null {
  return kind && SHOWN_KINDS.includes(kind) ? `detail.promoter.type.${kind}` : null
}
