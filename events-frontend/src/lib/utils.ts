import type { ClassValue } from 'clsx'
import { clsx } from 'clsx'
import { extendTailwindMerge } from 'tailwind-merge'

/**
 * The names our `@theme` block in `main.css` adds. Without them tailwind-merge reads `text-meta` as a
 * colour and drops it beside `text-muted-foreground` (#2027). `utils.spec.ts` fails when the two drift.
 */
const twMerge = extendTailwindMerge({
  extend: {
    theme: {
      text: ['meta', 'body', 'prose', 'card-title', 'lede', 'section', 'page'],
      font: ['wordmark', 'heading'],
      tracking: ['eyebrow'],
    },
  },
})

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs))
}

/**
 * Chrome shared by the native form controls in the filter bars (BaseInput and BaseSelect, the
 * only two that should reference it), here rather than duplicated or turned into an `@apply` rule.
 */
export const FIELD_CLASS =
  'h-8 rounded-lg border border-border bg-background px-2 text-body outline-none focus-visible:ring-3 focus-visible:ring-ring/50'

/**
 * A native checkbox: the notification toggles, the report form's problems and the filter options.
 * Its own constant rather than {@link FIELD_CLASS}, because a box takes a size and no padding.
 * A label that top-aligns its text adds `mt-1` at the call site, because the margin is layout.
 */
export const CHECKBOX_CLASS =
  'size-4 shrink-0 rounded border-border accent-primary outline-none focus-visible:ring-3 focus-visible:ring-ring/50'

/**
 * A link inside a line of text, in the accent colour. It matches the `link` variant of `Button`,
 * which also adds the button's box, so a plain anchor takes this instead.
 */
export const TEXT_LINK_CLASS = 'text-primary underline-offset-4 hover:underline'

/**
 * An interactive card: the event and venue tiles, which are links. No border, fill, shadow or
 * radius: the poster is the card, and space separates one from the next (#1246). The reveal
 * belongs to the poster box below.
 */
export const CARD_CLASS = 'group flex flex-col gap-3'

/**
 * The poster box inside a {@link CARD_CLASS} card: it bleeds through the page shell's `p-4` below
 * `sm`, and names the group the touch reveal hangs off (`useViewportFocus`).
 */
export const CARD_POSTER_CLASS = 'group/poster -mx-4 sm:mx-0'

/**
 * A grid of {@link CARD_CLASS} cards. The two gaps differ: across, a gap parts two posters with
 * their own edges; down, it parts one card's last line of text from the next poster.
 */
export const CARD_GRID_CLASS = 'grid grid-cols-1 gap-x-8 gap-y-12 sm:grid-cols-2'

/**
 * The curated pick that leads `/events` (#1262): both columns of a {@link CARD_GRID_CLASS} from
 * `sm` up. Its poster box is {@link CARD_LEAD_POSTER_ASPECT}, because a 3:2 box twice as wide is
 * 640 px tall for one event at 1440 px.
 */
export const CARD_LEAD_CLASS = 'sm:col-span-2'

/** The lead card's poster box, the `--aspect-lead` token: 480 px tall at 1440 px, 179 px at 390 px (#1247). */
export const CARD_LEAD_POSTER_ASPECT = 'aspect-lead'

/**
 * A list of text rows with hairlines between and around them: the compact view's
 * `EventRow`/`VenueRow` lists, the account page's lists and the festival notices.
 */
export const CARD_LIST_CLASS = 'divide-y divide-border border-y border-border'

/** A panel over a map, such as a venue's events or a group marker's venues: the map stays in view. */
export const MAP_PANEL_CLASS =
  'absolute inset-x-2 bottom-8 z-20 max-h-56 space-y-2 overflow-y-auto rounded-lg border border-border bg-background/95 p-3 sm:inset-x-auto sm:bottom-3 sm:left-3 sm:max-h-80 sm:w-96'

/**
 * The two filter bars: chrome, not an object (#1240). One hairline rule, no horizontal padding,
 * because the page's own `p-4 sm:p-8` sets that edge.
 */
export const PANEL_CLASS = 'flex flex-wrap items-end gap-3 border-b border-border pb-4'

/**
 * The line between a filter bar and its results: the count on the left, the sort control on the
 * right. Sort sits here, not in the bar, because it orders the list and narrows nothing (#360).
 */
export const RESULTS_BAR_CLASS = 'flex flex-wrap items-center justify-between gap-x-3 gap-y-2'

/**
 * A page's `h1`, at the `text-page` step. `ProsePage` styles its slotted `h1` with the same three
 * utilities as `[&_h1]:` variants, because it does not render the element itself.
 */
export const PAGE_TITLE_CLASS = 'text-page font-bold tracking-tight'

/**
 * A section heading at the `text-section` step: the `h2`s of the home, week, map and venues pages,
 * and the account deletion dialog's title. `ProsePage` repeats it as `[&_h2]:` variants.
 */
export const SECTION_TITLE_CLASS = 'text-section font-bold tracking-tight'
