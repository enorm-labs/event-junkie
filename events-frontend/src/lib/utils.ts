import type { ClassValue } from 'clsx'
import { clsx } from 'clsx'
import { twMerge } from 'tailwind-merge'

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs))
}

/**
 * Chrome shared by the native form controls in the filter bars — see BaseInput and BaseSelect,
 * the only two things that should reference it. It lives here rather than being duplicated in
 * both components (and rather than becoming an `@apply` rule, which would move the styling out
 * of the components that own it).
 */
export const FIELD_CLASS =
  'h-8 rounded-lg border border-border bg-background px-2 text-sm outline-none focus-visible:ring-3 focus-visible:ring-ring/50'

/**
 * An interactive card — the event and venue tiles, which are links. Extracted, like
 * {@link FIELD_CLASS}, because both cards carry it verbatim.
 *
 * No border, fill, shadow or radius: the poster is the card, and a container drawn around a picture
 * competes with it. Space is what separates one card from the next (see #1246). The reveal, from
 * grayscale to colour, belongs to the poster box below.
 */
export const CARD_CLASS = 'group flex flex-col gap-3'

/**
 * The poster box inside a {@link CARD_CLASS} card: it bleeds through the page shell's `p-4` below
 * `sm`, and names the group the touch reveal hangs off (`useViewportFocus`).
 */
export const CARD_POSTER_CLASS = 'group/poster -mx-4 sm:mx-0'

/**
 * A grid of {@link CARD_CLASS} cards.
 *
 * The two gaps differ because they separate different things. Across, a gap parts two posters, and
 * a poster has its own edge. Down, it parts one card's last line of text from the next card's
 * poster, which without chrome would otherwise read as one running column.
 */
export const CARD_GRID_CLASS = 'grid grid-cols-1 gap-x-8 gap-y-12 sm:grid-cols-2'

/** The compact view's list of `EventRow`/`VenueRow` text rows: one column, hairlines between. */
export const CARD_LIST_CLASS = 'divide-y divide-border border-y border-border'

/**
 * The two filter bars: chrome, not an object (see #1240). One hairline rule, which is the only line
 * on a list page. No horizontal padding, because the page's own `p-4 sm:p-8` sets that edge.
 */
export const PANEL_CLASS = 'flex flex-wrap items-end gap-3 border-b border-border pb-4'
