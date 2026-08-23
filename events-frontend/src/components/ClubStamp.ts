import { localisedView } from '@/views/localisedView'

/**
 * The club stamp lockup, resolved to the active locale's artwork.
 *
 * One component per locale rather than one with a swapped caption: the caption is **outlined glyph
 * contours, not text**, so nothing here is translatable at runtime — and the German line needs its
 * own sizing, so the two are different drawings (docs/BRANDING.md §4b).
 *
 * Wrapped at **module scope** because `defineAsyncComponent` returns a fresh identity per call, and
 * one inside a `<script setup>` would remount the stamp on every render of its page.
 *
 * Each locale is its own lazy chunk — ~50 kB gzipped each, and before this split the single stamp
 * had landed in the **entry** chunk, downloaded on every route rather than the one that shows it.
 */
export default localisedView({
  en: () => import('@/components/ClubStamp.en.vue'),
  de: () => import('@/components/ClubStamp.de.vue'),
})
