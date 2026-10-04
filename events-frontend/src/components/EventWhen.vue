<script lang="ts" setup>
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import SectionLabel from '@/components/SectionLabel.vue'
import type { EventDetail } from '@/api/types'
import { formatTime } from '@/lib/format'
import { useFormat } from '@/composables/useFormat'

const props = defineProps<{ event: EventDetail }>()

const { t } = useI18n()
const { formatEventDates, formatEventEnd } = useFormat()

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
</script>

<template>
  <div class="space-y-1">
    <SectionLabel>{{ t('events.detail.when.label') }}</SectionLabel>
    <p v-if="dates" class="font-medium">{{ dates }}</p>
    <p v-if="doors" class="text-body">{{ doors }}</p>
    <p v-if="start" class="text-body">
      {{ start
      }}<span v-if="estimated" class="text-muted-foreground">
        · {{ t('events.detail.when.estimate') }}</span
      >
    </p>
    <p v-if="end" class="text-body">{{ end }}</p>
  </div>
</template>
