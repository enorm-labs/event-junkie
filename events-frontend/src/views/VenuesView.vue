<script lang="ts" setup>
import { computed, onMounted, ref, watch } from 'vue'
import { type LocationQueryRaw, useRoute, useRouter } from 'vue-router'
import { Button } from '@/components/ui/button'
import BaseInput from '@/components/BaseInput.vue'
import BaseSelect from '@/components/BaseSelect.vue'
import SectionLabel from '@/components/SectionLabel.vue'
import VenueCard from '@/components/VenueCard.vue'
import { useVenueSearch, type VenueSearchParams } from '@/composables/useVenues'
import { DISTRICTS } from '@/lib/districts'
import { useI18n } from 'vue-i18n'
import { PANEL_CLASS } from '@/lib/utils'

const PAGE_SIZE = 24

const route = useRoute()
const router = useRouter()

// Filters live in the URL query so the list is shareable and survives back/forward.
function queryString(key: string): string {
  const value = route.query[key]
  return typeof value === 'string' ? value : ''
}

const params = computed<VenueSearchParams>(() => ({
  q: queryString('q') || undefined,
  district: queryString('district') || undefined,
  page: queryString('page') ? Number(queryString('page')) : 0,
  size: PAGE_SIZE,
}))

const { data: page, error, loading, run } = useVenueSearch(() => params.value)

// The search box is a local draft applied on submit, kept in sync with the URL.
const search = ref(queryString('q'))
watch(
  () => route.query.q,
  () => {
    search.value = queryString('q')
  },
)

const currentPage = computed(() => page.value?.page ?? 0)
const totalPages = computed(() => page.value?.totalPages ?? 0)

function applyFilters(patch: LocationQueryRaw) {
  // Any filter change resets to the first page; empty values drop out of the URL.
  const next: LocationQueryRaw = { ...route.query, ...patch, page: undefined }
  for (const key of Object.keys(next)) {
    if (next[key] === '' || next[key] === undefined) delete next[key]
  }
  router.push({ query: next })
}

function goToPage(target: number) {
  const next: LocationQueryRaw = { ...route.query, page: target > 0 ? String(target) : undefined }
  if (next.page === undefined) delete next.page
  router.push({ query: next })
}

onMounted(run)
watch(() => route.query, run, { deep: true })

const { t } = useI18n()
</script>

<template>
  <main class="mx-auto max-w-5xl space-y-6 p-8">
    <header class="space-y-1">
      <SectionLabel as="p">{{ t('venues.eyebrow') }}</SectionLabel>
      <h1 class="text-3xl font-bold tracking-tight">{{ t('venues.title') }}</h1>
      <p class="text-muted-foreground">{{ t('venues.subtitle') }}</p>
    </header>

    <div :class="PANEL_CLASS">
      <form class="flex gap-2" @submit.prevent="applyFilters({ q: search })">
        <BaseInput
          v-model="search"
          :placeholder="t('venues.searchPlaceholder')"
          class="px-3"
          type="search"
        />
        <Button type="submit" variant="outline">{{ t('common.actions.search') }}</Button>
      </form>

      <BaseSelect
        :aria-label="t('venues.byDistrict')"
        :model-value="queryString('district')"
        @change="applyFilters({ district: ($event.target as HTMLSelectElement).value })"
      >
        <option value="">{{ t('venues.allDistricts') }}</option>
        <option v-for="d in DISTRICTS" :key="d.slug" :value="d.slug">{{ d.label }}</option>
      </BaseSelect>
    </div>

    <p v-if="loading" class="text-sm text-muted-foreground">
      {{ t('common.states.loadingVenues') }}
    </p>
    <p v-else-if="error" class="text-sm text-destructive">{{ error }}</p>
    <p v-else-if="!page?.content?.length" class="text-sm text-muted-foreground">
      {{ t('venues.empty') }}
    </p>
    <template v-else>
      <p class="text-sm text-muted-foreground">
        {{ t('venues.resultCount', { count: page.totalElements }) }}
      </p>
      <div class="grid grid-cols-1 gap-3 sm:grid-cols-2">
        <!-- Second level of the outline: nothing sits between the page `h1` and this grid. -->
        <VenueCard v-for="venue in page.content" :key="venue.slug" :venue="venue" as="h2" />
      </div>

      <div v-if="totalPages > 1" class="flex items-center justify-between gap-3 pt-2">
        <Button :disabled="currentPage <= 0" variant="outline" @click="goToPage(currentPage - 1)">
          {{ t('common.actions.previous') }}
        </Button>
        <span class="text-sm text-muted-foreground">
          {{ t('common.pagination.pageOf', { current: currentPage + 1, total: totalPages }) }}
        </span>
        <Button
          :disabled="currentPage >= totalPages - 1"
          variant="outline"
          @click="goToPage(currentPage + 1)"
        >
          {{ t('common.actions.next') }}
        </Button>
      </div>
    </template>
  </main>
</template>
