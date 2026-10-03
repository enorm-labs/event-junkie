import { computed } from 'vue'
import { useRoute } from 'vue-router'

import { DEFAULT_LOCALE, isLocale, type Locale, LOCALES, stripLocale } from '@/i18n/locales'

/** Native language names — a German speaker looks for "Deutsch", not "German". */
export const LOCALE_NAMES: Record<Locale, string> = {
  en: 'English',
  de: 'Deutsch',
}

/**
 * The locale in the URL, and the current page's address in each published locale. Shared by the
 * header's language menu and the footer's switcher, so both keep the query and the hash.
 */
export function useLocaleSwitch() {
  // `useRoute()` yields undefined when no router is installed, as in component tests; degrading to
  // the default locale keeps them router-free, as `useLocalePath` does.
  const route = useRoute() as ReturnType<typeof useRoute> | undefined

  const current = computed<Locale>(() => {
    const fromUrl = route?.params?.locale
    return isLocale(fromUrl) ? fromUrl : DEFAULT_LOCALE
  })

  /**
   * The current path with its locale segment swapped, query included: without it a filtered list
   * came back unfiltered in the other language (#1249).
   */
  function pathIn(locale: Locale): string {
    const rest = stripLocale(route?.path ?? '/')
    const params = new URLSearchParams()
    for (const [key, value] of Object.entries(route?.query ?? {})) {
      // A repeated parameter arrives as an array — `sort` is one, and the filters may become several.
      for (const one of Array.isArray(value) ? value : [value]) {
        if (one != null) params.append(key, String(one))
      }
    }
    const search = params.toString()
    return `${`/${locale}${rest}`.replace(/\/$/, '')}${search ? `?${search}` : ''}${route?.hash ?? ''}`
  }

  return { locales: LOCALES, current, pathIn }
}
