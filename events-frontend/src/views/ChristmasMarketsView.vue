<script lang="ts" setup>
/**
 * Berlin's Christmas markets in one list and on one map (#4972). Each market is an event entered by hand with the
 * `christmas-market` feature, which keeps it out of every other list (EVENT_SCOPE.md §3.6).
 */
import { computed, defineAsyncComponent, onMounted, ref, useId } from 'vue'
import { useI18n } from 'vue-i18n'
import { RouterLink } from 'vue-router'
import type { EventSummary } from '@/api/types'
import BaseInput from '@/components/BaseInput.vue'
import ClearAllFilters from '@/components/ClearAllFilters.vue'
import ErrorLine from '@/components/ErrorLine.vue'
import EventRow from '@/components/EventRow.vue'
import ListPage from '@/components/ListPage.vue'
import MapPanel from '@/components/MapPanel.vue'
import MultiSelectFilter from '@/components/MultiSelectFilter.vue'
import { Button } from '@/components/ui/button'
import { useAsync } from '@/composables/useAsync'
import { searchEvents } from '@/composables/useEvents'
import { useFormat } from '@/composables/useFormat'
import { useLocalePath } from '@/composables/useLocalePath'
import { useMarketFilters } from '@/composables/useMarketFilters'
import { CHRISTMAS_MARKET, MARKET_TODAY, MARKET_WEEKEND } from '@/lib/christmasMarkets'
import { todayIso, venueLocationLine } from '@/lib/format'
import { type MapPin, venuePin } from '@/lib/mapPins'
import {
  CARD_LIST_CLASS,
  cn,
  FIELD_CLASS,
  PANEL_CLASS,
  SECTION_TITLE_CLASS,
  TEXT_LINK_CLASS,
} from '@/lib/utils'

const VenueMap = defineAsyncComponent(() => import('@/components/VenueMap.vue'))

/** The API's page cap. A season has about thirty markets. */
const PAGE_SIZE = 100

const { t, locale } = useI18n()
const { formatDayRange } = useFormat()
const localePath = useLocalePath()

const today = todayIso()
const markets = useAsync<EventSummary[]>(
  async () => {
    const page = await searchEvents({
      feature: [CHRISTMAS_MARKET],
      from: today,
      running: true,
      size: PAGE_SIZE,
    })
    return page.content ?? []
  },
  'errors.subject.christmasMarkets',
  () => `/api/events?feature=${CHRISTMAS_MARKET}&from=${today}&running=true&size=${PAGE_SIZE}`,
)
onMounted(markets.run)

const events = computed(() => markets.data.value ?? [])

const {
  day,
  date,
  dateBounds,
  free,
  districts,
  filtered,
  districtCounts,
  districtOptions,
  setDay,
  setFree,
  setDistricts,
} = useMarketFilters(events)

/** The markets the filters keep, by name: the list, the count and the map all read this one list. */
const listed = computed(() =>
  [...filtered.value].sort((a, b) =>
    (a.shown.title ?? '').localeCompare(b.shown.title ?? '', locale.value),
  ),
)

const dayWords = computed(() => [
  { value: MARKET_TODAY, label: t('christmasMarkets.filters.today') },
  { value: MARKET_WEEKEND, label: t('christmasMarkets.filters.weekend') },
])
const dateId = useId()

/** Opens the browser's calendar on a click anywhere in the field, as the events filter bar does. */
function openDatePicker(event: MouseEvent) {
  const input = event.currentTarget as HTMLInputElement & { showPicker?: () => void }
  input.showPicker?.()
}

const selected = ref<string | null>(null)
const mapUnavailable = ref(false)

const pins = computed<MapPin[]>(() =>
  listed.value
    .map(({ shown }) =>
      shown.venue
        ? venuePin(shown.venue, shown.title ?? '', undefined, { note: dayRange(shown) })
        : null,
    )
    .filter((pin): pin is MapPin => pin !== null),
)

/** A market sits at its own venue, so the selected pin names one market. */
const selectedMarket = computed(() =>
  listed.value.find(({ shown }) => shown.venue?.slug === selected.value),
)

/** Every block of the selected market, as "28.–29. Nov. · 5.–6. Dez.". */
const selectedBlocks = computed(() =>
  (selectedMarket.value?.blocks ?? []).map(dayRange).filter(Boolean).join(' · '),
)

function dayRange(market: EventSummary): string {
  return market.eventDate
    ? formatDayRange(market.eventDate, market.endDate ?? market.eventDate)
    : ''
}
</script>

<template>
  <ListPage :subtitle="t('christmasMarkets.subtitle')" :title="t('christmasMarkets.title')">
    <p v-if="markets.loading.value" class="text-body text-muted-foreground">
      {{ markets.retrying.value ? t('errors.retrying') : t('common.states.loading') }}
    </p>
    <ErrorLine v-else-if="markets.error.value">{{ markets.error.value }}</ErrorLine>
    <p v-else-if="!events.length" class="text-body text-muted-foreground">
      {{ t('christmasMarkets.empty') }}
    </p>
    <template v-else>
      <div :class="PANEL_CLASS">
        <div class="flex flex-wrap items-center gap-2">
          <Button
            v-for="word in dayWords"
            :key="word.value"
            :aria-pressed="day === word.value"
            :variant="day === word.value ? 'default' : 'outline'"
            type="button"
            @click="setDay(day === word.value ? '' : word.value)"
          >
            {{ word.label }}
          </Button>
          <label
            :class="
              cn(
                FIELD_CLASS,
                'flex min-w-0 items-center gap-1.5 focus-within:ring-3 focus-within:ring-ring/50',
              )
            "
            :for="dateId"
          >
            <span class="text-muted-foreground">{{ t('christmasMarkets.filters.date') }}</span>
            <BaseInput
              :id="dateId"
              :max="dateBounds.max"
              :min="dateBounds.min"
              :model-value="date"
              class="h-full min-w-0 rounded-none border-0 bg-transparent px-0 focus-visible:ring-0"
              type="date"
              @change="setDay(($event.target as HTMLInputElement).value)"
              @click="openDatePicker"
            />
          </label>
        </div>
        <MultiSelectFilter
          :all-label="t('christmasMarkets.filters.allDistricts')"
          :clear-label="t('christmasMarkets.filters.clearDistricts')"
          :count-label="(n) => t('christmasMarkets.filters.districtsSelected', { n })"
          :counts="districtCounts"
          :label="t('christmasMarkets.filters.byDistrict')"
          :options="districtOptions"
          :selected="districts"
          @change="setDistricts"
        />
        <Button
          :aria-pressed="free"
          :variant="free ? 'default' : 'outline'"
          type="button"
          @click="setFree(!free)"
        >
          {{ t('christmasMarkets.filters.free') }}
        </Button>
        <ClearAllFilters />
      </div>

      <div v-if="!listed.length" class="space-y-3">
        <p class="text-body text-muted-foreground">{{ t('christmasMarkets.emptyFiltered') }}</p>
        <ClearAllFilters empty-state />
      </div>
      <template v-else>
        <p class="text-body text-muted-foreground">
          {{ t('christmasMarkets.resultCount', { count: listed.length }) }}
        </p>

        <p v-if="mapUnavailable" class="text-body text-muted-foreground">
          {{ t('map.unavailable') }}
        </p>
        <VenueMap
          v-else-if="pins.length"
          v-model:selected="selected"
          :pins="pins"
          @unavailable="mapUnavailable = true"
        >
          <MapPanel
            v-if="selectedMarket"
            :subtitle="venueLocationLine(selectedMarket.shown.venue)"
            :title="selectedMarket.shown.title ?? ''"
            :to="localePath(`/events/${selectedMarket.shown.slug}`)"
            @close="selected = null"
          >
            <p class="text-body text-muted-foreground">{{ selectedBlocks }}</p>
            <RouterLink
              :to="localePath(`/events/${selectedMarket.shown.slug}`)"
              :class="['inline-block text-body', TEXT_LINK_CLASS]"
            >
              {{ t('christmasMarkets.details') }}
            </RouterLink>
          </MapPanel>
        </VenueMap>

        <section class="space-y-2">
          <h2 :class="SECTION_TITLE_CLASS">{{ t('christmasMarkets.listHeading') }}</h2>
          <div :class="CARD_LIST_CLASS">
            <EventRow v-for="market in listed" :key="market.key" :event="market.shown" as="h3" />
          </div>
        </section>
      </template>
    </template>
  </ListPage>
</template>
