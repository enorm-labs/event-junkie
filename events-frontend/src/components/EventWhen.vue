<script lang="ts" setup>
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import SectionLabel from '@/components/SectionLabel.vue'
import type { EventDetail } from '@/api/types'
import { formatTime } from '@/lib/format'
import { useEventChanges } from '@/composables/useEventChanges'
import { useFormat } from '@/composables/useFormat'

const props = defineProps<{ event: EventDetail }>()

const { t } = useI18n()
const { formatEventDates, formatEventEnd } = useFormat()
const { changeLines } = useEventChanges()

const dates = computed(() => (props.event.eventDate ? formatEventDates(props.event) : ''))
const doors = computed(() =>
  props.event.doorsTime ? t('events.card.doors', { time: formatTime(props.event.doorsTime) }) : '',
)
// The BFF sends the guess only when the venue stated neither start nor doors.
const estimated = computed(() => !props.event.startTime && !!props.event.assumedStartTime)
const start = computed(() => {
  if (props.event.startTime)
    return t('events.detail.when.start', { time: formatTime(props.event.startTime) })
  if (props.event.assumedStartTime)
    return t('events.detail.when.start', {
      time: t('events.card.assumed', { time: formatTime(props.event.assumedStartTime) }),
    })
  return ''
})
const end = computed(() => {
  const text = formatEventEnd(props.event)
  return text ? t('events.card.until', { time: text }) : ''
})

// The venue change sits under the venue's name instead (#2725).
const changes = computed(() => changeLines(props.event.changes, (field) => field !== 'VENUE'))
</script>

<template>
  <div class="space-y-1">
    <SectionLabel>{{ t('events.detail.when.label') }}</SectionLabel>
    <p v-if="dates" class="text-body font-medium">{{ dates }}</p>
    <p v-if="doors" class="text-body">{{ doors }}</p>
    <p v-if="start" class="text-body">
      {{ start
      }}<span v-if="estimated" class="text-muted-foreground">
        · {{ t('events.detail.when.estimate') }}</span
      >
    </p>
    <p v-if="end" class="text-body">{{ end }}</p>
    <ul v-if="changes.length" :aria-label="t('events.detail.when.changesLabel')" class="pt-1">
      <li v-for="(change, index) in changes" :key="index" class="text-body">
        <span class="font-medium">{{ change.text }}</span
        ><span class="text-muted-foreground"> · {{ change.ago }}</span>
      </li>
    </ul>
  </div>
</template>
