<script lang="ts" setup>
/**
 * Switches between the published locales. Links rather than a `<select>`: the locale lives in the
 * URL (ADR-013 §Decision 2), so each option is a different address that middle-click, "copy link"
 * and a crawler can follow. The current page is preserved across the switch.
 */
import { computed } from 'vue'
import { useRoute } from 'vue-router'

import { DEFAULT_LOCALE, isLocale, type Locale, LOCALES, stripLocale } from '@/i18n/locales'

const props = withDefaults(
  defineProps<{
    /**
     * Renders `EN · DE` instead of `English · Deutsch`, without a `nav` landmark: the full names do
     * not fit a ~390px viewport (the overflow guard in e2e/smoke.spec.ts), and a second landmark
     * named "Language" would collide with the footer's. The compact form lives inside the header's
     * own navigation.
     */
    compact?: boolean
  }>(),
  { compact: false },
)

// `useRoute()` yields undefined when no router is installed, as in component tests; degrading to
// the default locale keeps them router-free, as `useLocalePath` does.
const route = useRoute() as ReturnType<typeof useRoute> | undefined

/** Native language names — a German speaker looks for "Deutsch", not "German". */
const LOCALE_NAMES: Record<Locale, string> = {
  en: 'English',
  de: 'Deutsch',
}

/**
 * `EN`/`DE` stays legible at `text-xs` but is a poor accessible name, so the link keeps the full
 * native name as `aria-label` and `title`, as the header's icon controls do.
 */
const shortName = (locale: Locale) => locale.toUpperCase()

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
</script>

<template>
  <!-- `nav` only in the full form: the footer contributes several landmarks and each must be
       distinguishable. The compact form is a plain wrapper inside the header's own nav, so it adds
       no landmark. `aria-current` marks the active language in both. -->
  <component
    :is="props.compact ? 'span' : 'nav'"
    :class="['flex items-center', props.compact ? 'gap-1 text-xs' : 'gap-2 text-sm']"
    v-bind="props.compact ? {} : { 'aria-label': $t('common.locale.label') }"
  >
    <template v-for="(locale, index) in LOCALES" :key="locale">
      <span v-if="index > 0" aria-hidden="true" class="text-muted-foreground">·</span>
      <a
        :aria-current="locale === current ? 'true' : undefined"
        :aria-label="props.compact ? LOCALE_NAMES[locale] : undefined"
        :class="
          locale === current
            ? 'font-medium text-foreground'
            : 'text-muted-foreground hover:text-foreground'
        "
        :href="pathIn(locale)"
        :hreflang="locale"
        :lang="locale"
        :title="props.compact ? LOCALE_NAMES[locale] : undefined"
      >
        {{ props.compact ? shortName(locale) : LOCALE_NAMES[locale] }}
      </a>
    </template>
  </component>
</template>
