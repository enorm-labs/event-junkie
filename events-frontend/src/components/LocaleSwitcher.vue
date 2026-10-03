<script lang="ts" setup>
/**
 * The footer's language switch. Links rather than a `<select>`: the locale lives in the URL
 * (ADR-013 §Decision 2), so each option is a different address that middle-click, "copy link" and
 * a crawler can follow. The current page is preserved across the switch. The header carries the
 * same links behind an icon button, LocaleMenu.
 */
import { LOCALE_NAMES, useLocaleSwitch } from '@/composables/useLocaleSwitch'

const { locales, current, pathIn } = useLocaleSwitch()
</script>

<template>
  <nav :aria-label="$t('common.locale.label')" class="flex items-center gap-2 text-body">
    <template v-for="(locale, index) in locales" :key="locale">
      <span v-if="index > 0" aria-hidden="true" class="text-muted-foreground">·</span>
      <a
        :aria-current="locale === current ? 'true' : undefined"
        :class="
          locale === current
            ? 'font-medium text-foreground'
            : 'text-muted-foreground hover:text-foreground'
        "
        :href="pathIn(locale)"
        :hreflang="locale"
        :lang="locale"
      >
        {{ LOCALE_NAMES[locale] }}
      </a>
    </template>
  </nav>
</template>
