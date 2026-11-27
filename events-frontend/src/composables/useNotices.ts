import { ref } from 'vue'

import notices from '@/assets/notices.json'

/**
 * The attribution list behind `/legal/notices`, shared by both language versions of that page.
 *
 * The page is per-locale prose (see views/localisedView.ts), but *this* is not prose — it is the
 * lazy-expansion behaviour and the coordinate formatting, which `NoticesLicenceGroups` renders once
 * for both languages. Two copies would be two places for the same bug.
 */

export interface Component {
  name: string
  version: string | null
  url: string | null
  ecosystem: string
}

export interface LicenseGroup {
  license: string
  url: string | null
  components: Component[]
}

export function useNotices() {
  const groups = notices.licenses as LicenseGroup[]

  // Collapsed by default: expanded, the MIT group alone is several hundred rows and the page
  // becomes unnavigable. The <details> element gives keyboard and screen-reader behaviour for free.
  const openGroups = ref(new Set<string>())

  function toggle(license: string, open: boolean) {
    if (open) openGroups.value.add(license)
    else openGroups.value.delete(license)
  }

  /**
   * The separator between a component's name and its version, in the form its own ecosystem uses:
   * `vue@3.5.41` for npm, `org.jsoup:jsoup:1.23.1` for Maven coordinates. Rendering them adjacent
   * with only markup between produced `vue3.5.41` — Vue condenses the whitespace between elements
   * — and a bare space would not survive copy-paste as a usable coordinate either.
   */
  function versionSuffix(component: Component): string {
    if (!component.version) return ''
    return `${component.ecosystem === 'backend' ? ':' : '@'}${component.version}`
  }

  return { componentCount: notices.componentCount, groups, openGroups, toggle, versionSuffix }
}
