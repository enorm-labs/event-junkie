<script lang="ts" setup>
/**
 * Points to the Clubcommission's Tag der Clubkultur festival week (#2296). We cannot import its
 * programme yet — one venue per source — so the home page links out to it instead (#2297).
 *
 * A plain link and nothing from their domain: no logo, no image, so the visitor's browser makes no
 * third-party request until they click. No dismiss button either: remembering one needs storage,
 * and the window below retires the notice on its own.
 */
import { useI18n } from 'vue-i18n'
import { ArrowUpRight } from '@lucide/vue'
import { todayIso } from '@/lib/format'

const PROGRAMME_URL = 'https://tagderclubkultur.berlin/programm/'
const LAST_DAY = '2026-10-11'

const { t } = useI18n()
const visible = todayIso() <= LAST_DAY
</script>

<template>
  <aside
    v-if="visible"
    :aria-label="t('home.clubkultur.name')"
    class="space-y-1 border-y border-border py-4 text-body"
  >
    <p>
      <strong class="font-medium text-foreground">{{ t('home.clubkultur.name') }}</strong>
      <span class="text-muted-foreground"> · {{ t('home.clubkultur.dates') }}</span>
    </p>
    <p class="text-muted-foreground">{{ t('home.clubkultur.body') }}</p>
    <a
      :href="PROGRAMME_URL"
      class="inline-flex items-center gap-1 font-medium text-primary underline-offset-4 hover:underline"
      rel="noopener"
      target="_blank"
    >
      {{ t('home.clubkultur.link') }}
      <ArrowUpRight aria-hidden="true" class="size-4" />
    </a>
  </aside>
</template>
