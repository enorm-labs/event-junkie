import { type Component, computed, defineAsyncComponent, defineComponent, h } from 'vue'
import { useI18n } from 'vue-i18n'

import { DEFAULT_LOCALE, type Locale } from '@/i18n/locales'

/**
 * Routes a page to a **separate component per language** rather than one component that swaps its
 * prose through the message catalogue.
 *
 * Everywhere else the catalogue is right, and the key-parity test keeps it honest. These five pages
 * — About plus the four legal ones, ~1,600 words with inline links, `<strong>` and `<code>` inside
 * the paragraphs — would need HTML inside message strings or shattered sentences. The legal pages
 * carry the stronger reason: an imprint is a **document**, reviewed as one, and
 * `ImprintView.de.vue` reads start to finish as the German imprint.
 *
 * The cost is drift no test can catch. What is testable is that both carry the mandatory elements
 * (`views/legal/__tests__/legalViews.spec.ts`, per locale) and take their facts from
 * {@link module:@/lib/legal}. Each locale is its own lazy chunk, so nobody downloads both.
 * @param loaders one dynamic `import()` per published locale
 */
export function localisedView(loaders: Record<Locale, () => Promise<Component>>): Component {
  // Wrapped once at module scope rather than per render: `defineAsyncComponent` returns a new
  // component identity each call, and a fresh identity on every render would remount the page —
  // losing scroll position and re-running its setup on every reactive tick.
  const versions = Object.fromEntries(
    Object.entries(loaders).map(([locale, loader]) => [
      locale,
      defineAsyncComponent(loader as () => Promise<Component>),
    ]),
  ) as Record<Locale, Component>

  return defineComponent({
    name: 'LocalisedView',
    setup() {
      const { locale } = useI18n()
      // The router applies the URL's locale before this renders (`beforeEach` → `setI18nLocale`),
      // so this is already correct on first paint. The fallback covers nothing in the app and
      // everything in a unit test that mounts without a router.
      const version = computed(() => versions[locale.value as Locale] ?? versions[DEFAULT_LOCALE])
      return () => h(version.value)
    },
  })
}
