<script lang="ts" setup>
import { computed, onBeforeUnmount, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { Button } from '@/components/ui/button'
import ListenLinks from '@/components/ListenLinks.vue'
import LiveDot from '@/components/LiveDot.vue'
import SectionLabel from '@/components/SectionLabel.vue'
import type { EventDetail } from '@/api/types'
import { isOnNow } from '@/lib/format'
import { runningOrder, setStates, type RunningOrderSet } from '@/lib/runningOrder'
import { CARD_LIST_CLASS, cn, UNDERLINED_LINK_CLASS } from '@/lib/utils'
import { useFormat } from '@/composables/useFormat'
import { useLocalePath } from '@/composables/useLocalePath'

const props = defineProps<{ event: EventDetail }>()

const { t, te } = useI18n()
const localePath = useLocalePath()
const { formatWeekday } = useFormat()

// Lineup arrives in billing order already, but sort defensively so headliners stay first.
const lineup = computed(() =>
  [...(props.event.lineup ?? [])].sort((a, b) => (a.billingOrder ?? 0) - (b.billingOrder ?? 0)),
)

/**
 * Whether the lineup's role labels carry information: a co-bill like `Alibi + Onyon + Tense`
 * bills every act `HEADLINER`, and three "Headliner" tags say nothing the list does not. An
 * all-DJ night keeps its tags: `DJ` says the acts play records, not live.
 */
const showRoles = computed(() => lineup.value.some((entry) => entry.role !== 'HEADLINER'))

// Floors and set times where the venue published a running order (#2002); null for the rest.
const floors = computed(() => runningOrder(lineup.value))

// The clock the running order is read against, a minute at a time, so the dot moves on by itself.
const now = ref(Date.now())
const clock = setInterval(() => (now.value = Date.now()), 60_000)
onBeforeUnmount(() => clearInterval(clock))

// Which set plays now, and which are over, only while the night is on and still at its venue: a
// past night's sets are all over, and saying so adds nothing.
const setState = computed(() => {
  const e = props.event
  if (!floors.value || (e.status ?? 'SCHEDULED') !== 'SCHEDULED') return null
  return isOnNow(e) ? setStates(floors.value, now.value) : null
})

// The lineup sits above the tickets and the venue, so a festival bill opens folded to keep them near.
const LINEUP_FOLD_ABOVE = 10
const LINEUP_FOLDED_SIZE = 8
const lineupExpanded = ref(false)
const lineupFolded = computed(
  () => !lineupExpanded.value && lineup.value.length > LINEUP_FOLD_ABOVE,
)
const shownLineup = computed(() =>
  lineupFolded.value ? lineup.value.slice(0, LINEUP_FOLDED_SIZE) : lineup.value,
)
/** The running order cut to the folded size across its floors, in the order they print. */
const shownFloors = computed(() => {
  if (!floors.value || !lineupFolded.value) return floors.value
  let left = LINEUP_FOLDED_SIZE
  return floors.value.flatMap((floor) => {
    const sets = floor.sets.slice(0, left)
    left -= sets.length
    return sets.length ? [{ ...floor, sets }] : []
  })
})

/** The host of the page the lineup came from when it is not the venue's, credited under it (ADR-036). */
const lineupCredit = computed(() => {
  const url = props.event.lineupSourceUrl
  if (!url || !lineup.value.length) return null
  try {
    return { url, host: new URL(url).host }
  } catch {
    return null
  }
})

/** `Sun 08:30–12:30`: the weekday only where a new night begins, the end only where it is known. */
function setTimeLabel(set: RunningOrderSet): string {
  const day = set.opensNight ? `${formatWeekday(set.opensNight)} ` : ''
  const span = set.start ? `${set.start}${set.end ? `–${set.end}` : ''}` : ''
  return `${day}${span}`
}

/**
 * Whether the headliners print larger than the rest, as on a festival poster. Only a lineup that
 * mixes headliners with other roles has a billing to show; a co-bill or an all-DJ night stays level.
 */
const billed = computed(
  () => showRoles.value && lineup.value.some((entry) => entry.role === 'HEADLINER'),
)

function lineupNameClass(role: string | undefined): string {
  if (!billed.value) return 'text-card-title'
  return role === 'HEADLINER' ? 'text-lede' : 'text-body'
}

/**
 * A backend enum's label, falling back to the raw value: `ArtistRole` lives in `events-core` and
 * can gain a value in a release that ships first. `HEADLINER` is poor; `events.role.HEADLINER` is
 * a bug report.
 */
function enumLabel(namespace: string, value: string): string {
  const key = `${namespace}.${value}`
  return te(key) ? t(key) : value
}
</script>

<template>
  <section v-if="floors" class="space-y-3">
    <SectionLabel>{{ t('events.detail.runningOrder') }}</SectionLabel>
    <div v-for="floor in shownFloors" :key="floor.stage ?? ''" class="space-y-1">
      <h3 v-if="floor.stage" class="pt-2 text-body font-medium text-muted-foreground">
        {{ floor.stage }}
      </h3>
      <ul :class="CARD_LIST_CLASS">
        <li
          v-for="set in floor.sets"
          :key="set.entry.artist?.slug ?? set.entry.artist?.name"
          :class="[
            'flex items-baseline gap-3 py-3',
            setState?.get(set) === 'past' && 'text-muted-foreground',
          ]"
        >
          <span
            class="flex w-28 shrink-0 items-center gap-2 text-meta text-muted-foreground tabular-nums"
          >
            <LiveDot v-if="setState?.get(set) === 'live'" :label="t('events.detail.playingNow')" />
            <!-- The dot's room on every other timed row, so the times stay in one column. -->
            <span v-else-if="setState?.has(set)" class="size-2 shrink-0" aria-hidden="true" />
            {{ setTimeLabel(set) }}
          </span>
          <span
            :class="[
              'min-w-0 flex-1 text-card-title font-medium',
              setState?.get(set) === 'live' && 'text-primary',
            ]"
          >
            <RouterLink
              v-if="set.entry.artist?.slug"
              :to="localePath(`/artists/${set.entry.artist.slug}`)"
              class="hover:text-primary"
            >
              {{ set.entry.artist.name }}
            </RouterLink>
            <template v-else>{{ set.entry.artist?.name }}</template>
            <ListenLinks :artist="set.entry.artist" />
          </span>
          <span v-if="showRoles && set.entry.role" class="shrink-0 text-meta text-muted-foreground">
            {{ enumLabel('events.role', set.entry.role) }}
          </span>
        </li>
      </ul>
    </div>
  </section>

  <section v-else-if="lineup.length" class="space-y-3">
    <SectionLabel>{{ t('events.detail.lineup') }}</SectionLabel>
    <ul :class="CARD_LIST_CLASS">
      <li
        v-for="entry in shownLineup"
        :key="entry.artist?.slug ?? entry.artist?.name"
        class="flex items-baseline justify-between gap-3 py-3"
      >
        <span :class="cn(lineupNameClass(entry.role), 'min-w-0 font-medium')">
          <RouterLink
            v-if="entry.artist?.slug"
            :to="localePath(`/artists/${entry.artist.slug}`)"
            class="hover:text-primary"
          >
            {{ entry.artist.name }}
          </RouterLink>
          <template v-else>{{ entry.artist?.name }}</template>
          <ListenLinks :artist="entry.artist" />
        </span>
        <div class="flex shrink-0 items-center gap-2 text-meta text-muted-foreground">
          <span v-if="entry.stage">{{ entry.stage }}</span>
          <span v-if="entry.stage && showRoles && entry.role" aria-hidden="true">·</span>
          <span v-if="showRoles && entry.role">
            {{ enumLabel('events.role', entry.role) }}
          </span>
        </div>
      </li>
    </ul>
  </section>

  <Button
    v-if="lineupFolded"
    data-testid="lineup-show-all"
    type="button"
    variant="outline"
    @click="lineupExpanded = true"
  >
    {{ t('events.detail.lineupShowAll', { count: lineup.length }) }}
  </Button>

  <p v-if="lineupCredit" class="text-meta text-muted-foreground">
    {{ t('events.detail.lineupFrom') }}
    <a :class="UNDERLINED_LINK_CLASS" :href="lineupCredit.url" rel="noopener" target="_blank">{{
      lineupCredit.host
    }}</a>
  </p>
</template>
