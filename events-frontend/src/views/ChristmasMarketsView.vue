<script lang="ts" setup>
/**
 * Berlin's Christmas markets in one list and on one map (#4972). Each market is an event entered by hand with the
 * `christmas-market` feature, which keeps it out of every other list (EVENT_SCOPE.md §3.6).
 */
import { computed, defineAsyncComponent, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { RouterLink } from 'vue-router'
import type { EventSummary } from '@/api/types'
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
import { CHRISTMAS_MARKET } from '@/lib/christmasMarkets'
import { todayIso, venueLocationLine } from '@/lib/format'
import { type MapPin, venuePin } from '@/lib/mapPins'
import { CARD_LIST_CLASS, PANEL_CLASS, SECTION_TITLE_CLASS, TEXT_LINK_CLASS } from '@/lib/utils'

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

const sorted = computed(() =>
  [...(markets.data.value ?? [])].sort((a, b) =>
    (a.title ?? '').localeCompare(b.title ?? '', locale.value),
  ),
)

const { free, districts, filtered, districtCounts, districtOptions, setFree, setDistricts } =
  useMarketFilters(sorted)

const selected = ref<string | null>(null)
const mapUnavailable = ref(false)

const pins = computed<MapPin[]>(() =>
  filtered.value
    .map((market) =>
      market.venue
        ? venuePin(market.venue, market.title ?? '', undefined, { note: dayRange(market) })
        : null,
    )
    .filter((pin): pin is MapPin => pin !== null),
)

/** A market sits at its own venue, so the selected pin names one market. */
const selectedMarket = computed(() =>
  filtered.value.find((market) => market.venue?.slug === selected.value),
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
    <p v-else-if="!sorted.length" class="text-body text-muted-foreground">
      {{ t('christmasMarkets.empty') }}
    </p>
    <template v-else>
      <div :class="PANEL_CLASS">
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

      <div v-if="!filtered.length" class="space-y-3">
        <p class="text-body text-muted-foreground">{{ t('christmasMarkets.emptyFiltered') }}</p>
        <ClearAllFilters empty-state />
      </div>
      <template v-else>
        <p class="text-body text-muted-foreground">
          {{ t('christmasMarkets.resultCount', { count: filtered.length }) }}
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
            :subtitle="venueLocationLine(selectedMarket.venue)"
            :title="selectedMarket.title ?? ''"
            :to="localePath(`/events/${selectedMarket.slug}`)"
            @close="selected = null"
          >
            <p class="text-body text-muted-foreground">{{ dayRange(selectedMarket) }}</p>
            <RouterLink
              :to="localePath(`/events/${selectedMarket.slug}`)"
              :class="['inline-block text-body', TEXT_LINK_CLASS]"
            >
              {{ t('christmasMarkets.details') }}
            </RouterLink>
          </MapPanel>
        </VenueMap>

        <section class="space-y-2">
          <h2 :class="SECTION_TITLE_CLASS">{{ t('christmasMarkets.listHeading') }}</h2>
          <div :class="CARD_LIST_CLASS">
            <EventRow v-for="market in filtered" :key="market.slug" :event="market" as="h3" />
          </div>
        </section>
      </template>
    </template>
  </ListPage>
</template>
