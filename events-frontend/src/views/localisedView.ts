import { type Component, computed, defineAsyncComponent, defineComponent, h } from 'vue'
import { useI18n } from 'vue-i18n'

import { DEFAULT_LOCALE, type Locale } from '@/i18n/locales'

/**
 * Routes a page to a separate component per language rather than one that swaps its prose
 * through the catalogue. These five pages, About and the four legal ones, are ~1,600 words with
 * inline links and markup inside the paragraphs, and an imprint is a document reviewed as one:
 * `ImprintView.de.vue` reads start to finish as the German imprint. The cost is drift no test can
 * catch; what is testable is the mandatory elements per locale
 * (`views/legal/__tests__/legalViews.spec.ts`) and the facts from {@link module:@/lib/legal}.
 * Each locale is its own lazy chunk.
 * @param loaders one dynamic `import()` per published locale
 */
export function localisedView(loaders: Record<Locale, () => Promise<Component>>): Component {
  // Wrapped once at module scope: `defineAsyncComponent` returns a new component identity each
  // call, and a fresh identity per render would remount the page on every reactive tick.
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
      // The router applies the URL's locale before this renders (`beforeEach`, `setI18nLocale`); the
      // fallback covers a unit test that mounts without a router.
      const version = computed(() => versions[locale.value as Locale] ?? versions[DEFAULT_LOCALE])
      return () => h(version.value)
    },
  })
}
