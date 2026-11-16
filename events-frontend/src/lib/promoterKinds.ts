import type { PromoterDetail } from '@/api/types'

export type PromoterKind = NonNullable<PromoterDetail['kind']>

/**
 * The kinds a visitor follows a presenter for, in the order the filter lists them. They get a word on a
 * promoter page and card and an option in the `/promoters` filter (#5091); "promoter", "media" and the
 * rest say nothing new (#5080).
 */
export const SHOWN_KINDS: readonly PromoterKind[] = ['collective', 'label', 'party']

/** Whether [value] is one of the [SHOWN_KINDS]. */
export function isShownKind(value: string | null | undefined): value is PromoterKind {
  return SHOWN_KINDS.includes(value as PromoterKind)
}

/** The i18n key that names [kind] on a promoter page or card, or null for a kind that shows nothing. */
export function promoterKindKey(kind: PromoterDetail['kind'] | undefined): string | null {
  return isShownKind(kind) ? `detail.promoter.type.${kind}` : null
}
