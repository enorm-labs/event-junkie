<script lang="ts" setup>
/**
 * The header's language switch (#2516): an icon button like its neighbours, opening the locales as
 * links. Links, not menu items, so each stays an address (ADR-013 §Decision 2); the footer's
 * LocaleSwitcher is the copy a crawler sees, since this list renders only while open.
 */
import { useI18n } from 'vue-i18n'
import { Check, Languages } from '@lucide/vue'
import { Button } from '@/components/ui/button'
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover'
import { LOCALE_NAMES, useLocaleSwitch } from '@/composables/useLocaleSwitch'

const { t } = useI18n()
const { locales, current, pathIn } = useLocaleSwitch()
</script>

<template>
  <Popover>
    <PopoverTrigger as-child>
      <Button
        :aria-label="t('common.locale.label')"
        :title="t('common.locale.label')"
        size="icon"
        variant="outline"
      >
        <Languages />
      </Button>
    </PopoverTrigger>
    <PopoverContent align="end" class="w-auto min-w-40">
      <ul :aria-label="t('common.locale.label')">
        <li v-for="locale in locales" :key="locale">
          <a
            :aria-current="locale === current ? 'true' : undefined"
            :href="pathIn(locale)"
            :hreflang="locale"
            :lang="locale"
            class="flex items-center justify-between gap-4 rounded-md px-2 py-1.5 text-body hover:bg-accent hover:text-accent-foreground focus-visible:bg-accent focus-visible:outline-hidden aria-[current]:font-medium"
          >
            {{ LOCALE_NAMES[locale] }}
            <Check v-if="locale === current" aria-hidden="true" class="size-4" />
          </a>
        </li>
      </ul>
    </PopoverContent>
  </Popover>
</template>
