<script lang="ts" setup>
import { computed, type Component } from 'vue'
import {
  Cloud,
  CloudFog,
  CloudLightning,
  CloudMoon,
  CloudRain,
  CloudSnow,
  CloudSun,
  Moon,
  Sun,
} from '@lucide/vue'
import { useI18n } from 'vue-i18n'
import SectionLabel from '@/components/SectionLabel.vue'
import type { EventDetail } from '@/api/types'
import { eventCalendarSpan } from '@/lib/addToCalendar'
import { formatTime } from '@/lib/format'
import { useEventChanges } from '@/composables/useEventChanges'
import { useFormat } from '@/composables/useFormat'
import { useLocalePath } from '@/composables/useLocalePath'
import { TEXT_LINK_CLASS } from '@/lib/utils'

const props = defineProps<{ event: EventDetail }>()

const { t } = useI18n()
const { formatDate, formatEventDates, formatEventEnd } = useFormat()
const localePath = useLocalePath()
const { changeLines } = useEventChanges()

// A postponed or cancelled show does not happen on this date: the date is struck and its times
// hidden. A postponed row's new date links its row, or is the date the note named (ADR-056).
const postponed = computed(() => props.event.status === 'POSTPONED')
const offDate = computed(() => postponed.value || props.event.status === 'CANCELLED')
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

// The DWD forecast for the start hour, sent only for an open-air venue in season (ADR-057).
type Condition = NonNullable<NonNullable<EventDetail['weather']>['condition']>
const DAY_ICONS: Record<Condition, Component> = {
  CLEAR: Sun,
  PARTLY_CLOUDY: CloudSun,
  CLOUDY: Cloud,
  FOG: CloudFog,
  RAIN: CloudRain,
  SNOW: CloudSnow,
  THUNDERSTORM: CloudLightning,
}
// A summer sun sets after nine in Berlin and rises before five.
const NIGHT_ICONS: Partial<Record<Condition, Component>> = { CLEAR: Moon, PARTLY_CLOUDY: CloudMoon }
const weather = computed(() => {
  const forecast = props.event.weather
  if (!forecast?.condition || forecast.temperatureCelsius == null) return null
  const hour = Number((props.event.startTime ?? props.event.doorsTime ?? '12').slice(0, 2))
  const night = hour >= 21 || hour < 5
  const line = t('events.detail.when.weather.line', {
    condition: t(`events.detail.when.weather.condition.${forecast.condition}`),
    temperature: forecast.temperatureCelsius,
  })
  const rain =
    forecast.rainProbabilityPercent == null
      ? ''
      : t('events.detail.when.weather.rain', { percent: forecast.rainProbabilityPercent })
  return {
    icon: (night && NIGHT_ICONS[forecast.condition]) || DAY_ICONS[forecast.condition],
    text: rain ? `${line} · ${rain}` : line,
  }
})

// The venue change sits under the venue's name instead (#2725).
const changes = computed(() => changeLines(props.event.changes, (field) => field !== 'VENUE'))
// The calendar entry's own test, so the page and the entry never disagree; whole days get no line.
const endNotAnnounced = computed(() => {
  const span = eventCalendarSpan(props.event)
  return !end.value && !!span && !span.allDay && span.endEstimated
})
</script>

<template>
  <div class="space-y-1">
    <SectionLabel>{{ t('events.detail.when.label') }}</SectionLabel>
    <p v-if="dates && offDate" class="text-body text-muted-foreground">
      <del>{{ dates }}</del>
    </p>
    <p v-else-if="dates" class="text-body font-medium">{{ dates }}</p>
    <p v-if="postponed && event.postponedTo" class="text-body font-medium">
      {{ t('events.detail.when.postponedTo') }}
      <RouterLink :to="localePath(`/events/${event.postponedTo.slug}`)" :class="TEXT_LINK_CLASS">{{
        formatDate(event.postponedTo.eventDate)
      }}</RouterLink>
    </p>
    <p v-else-if="postponed && event.relocatedOn" class="text-body font-medium">
      {{ t('events.detail.when.postponedTo') }} {{ formatDate(event.relocatedOn) }}
    </p>
    <p v-else-if="postponed" class="text-body text-muted-foreground">
      {{ t('events.detail.when.postponed') }}
    </p>
    <template v-if="!offDate">
      <p v-if="doors" class="text-body">{{ doors }}</p>
      <p v-if="start" class="text-body">
        {{ start
        }}<span v-if="estimated" class="text-muted-foreground">
          · {{ t('events.detail.when.estimate') }}</span
        >
      </p>
      <p v-if="end" class="text-body">{{ end }}</p>
      <p v-if="endNotAnnounced" class="text-body text-muted-foreground">
        {{ t('events.detail.when.endNotAnnounced') }}
      </p>
      <p v-if="weather" class="flex items-center gap-1.5 text-body" data-testid="weather">
        <component :is="weather.icon" aria-hidden="true" class="size-4 shrink-0" />
        <span>{{ weather.text }}</span>
      </p>
    </template>
    <ul v-if="changes.length" :aria-label="t('events.detail.when.changesLabel')" class="pt-1">
      <li v-for="(change, index) in changes" :key="index" class="text-body">
        <span class="font-medium">{{ change.text }}</span
        ><span v-if="change.ago" class="text-muted-foreground"> · {{ change.ago }}</span>
      </li>
    </ul>
  </div>
</template>
