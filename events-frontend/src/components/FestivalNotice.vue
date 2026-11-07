<script lang="ts" setup>
/**
 * Points to the festivals across many venues, read from the BFF (#3272): one notice each, with a
 * hairline between them. A plain link and nothing from a festival's domain: no logo, no image, so
 * the browser makes no third-party request until the visitor clicks. No dismiss button either,
 * because remembering one needs storage.
 */
import { computed, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { ArrowUpRight } from '@lucide/vue'
import type { Festival } from '@/api/types'
import { useCurrentFestivals } from '@/composables/useCurrentFestivals'
import { useFormat } from '@/composables/useFormat'
import { CARD_LIST_CLASS, TEXT_LINK_CLASS } from '@/lib/utils'

const { t, locale } = useI18n()
const { formatDayRange } = useFormat()
const { festivals, run } = useCurrentFestivals()

onMounted(run)

type Shown = Festival & Required<Pick<Festival, 'name' | 'url' | 'firstDay' | 'lastDay'>>

const shown = computed(() =>
  festivals.value.filter((f): f is Shown => !!(f.name && f.url && f.firstDay && f.lastDay)),
)

function noticeOf(festival: Festival) {
  return locale.value === 'de' ? festival.noticeDe : festival.noticeEn
}
</script>

<template>
  <div v-if="shown.length" :class="CARD_LIST_CLASS">
    <aside
      v-for="festival in shown"
      :key="festival.slug"
      :aria-label="festival.name"
      class="space-y-1 py-4 text-body"
      data-testid="festival-notice"
    >
      <p>
        <strong class="font-medium text-foreground">{{ festival.name }}</strong>
        <span class="text-muted-foreground">
          · {{ formatDayRange(festival.firstDay, festival.lastDay) }}</span
        >
      </p>
      <p v-if="noticeOf(festival)" class="text-muted-foreground">{{ noticeOf(festival) }}</p>
      <a
        :href="festival.url"
        :class="['inline-flex items-center gap-1 font-medium', TEXT_LINK_CLASS]"
        rel="noopener"
        target="_blank"
      >
        {{ t('home.festival.link') }}
        <ArrowUpRight aria-hidden="true" class="size-4" />
      </a>
    </aside>
  </div>
</template>
