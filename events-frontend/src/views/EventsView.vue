<script lang="ts" setup>
import { computed, onMounted, watch } from 'vue'
import { type LocationQueryRaw, useRoute, useRouter } from 'vue-router'
import { Button } from '@/components/ui/button'
import EventCard from '@/components/EventCard.vue'
import EventFilterBar from '@/components/EventFilterBar.vue'
import SectionLabel from '@/components/SectionLabel.vue'
import { type EventSearchParams, useEventSearch } from '@/composables/useEvents'
import { useEventFilters } from '@/composables/useEventFilters'
import { useI18n } from 'vue-i18n'

const PAGE_SIZE = 20

const route = useRoute()
const router = useRouter()

// Filters live in the URL query so list views are shareable and survive back/forward; the
// filter bar writes them and `useEventFilters` reads them back (see EventFilterBar.vue).
const { queryString, filters, dateRange } = useEventFilters()

// The list owns its dates, so it merges the range in; the BFF defaults to today onwards when
// both bounds are absent, which is the right empty state here.
const params = computed<EventSearchParams>(() => ({
  ...filters.value,
  ...dateRange.value,
  page: queryString('page') ? Number(queryString('page')) : 0,
  size: PAGE_SIZE,
}))

const { data: page, error, loading, run } = useEventSearch(() => params.value)

const currentPage = computed(() => page.value?.page ?? 0)
const totalPages = computed(() => page.value?.totalPages ?? 0)

function goToPage(target: number) {
  // Unlike filter changes, paging keeps the current filters and only moves the page.
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
      <SectionLabel as="p">{{ t('events.eyebrow') }}</SectionLabel>
      <h1 class="text-3xl font-bold tracking-tight">{{ t('events.title') }}</h1>
      <p class="text-muted-foreground">{{ t('events.subtitle') }}</p>
    </header>

    <EventFilterBar />

    <p v-if="loading" class="text-sm text-muted-foreground">{{ t('common.states.loading') }}</p>
    <p v-else-if="error" class="text-sm text-destructive">{{ error }}</p>
    <p v-else-if="!page?.content?.length" class="text-sm text-muted-foreground">
      {{ t('events.empty') }}
    </p>
    <template v-else>
      <p class="text-sm text-muted-foreground">
        {{ t('events.resultCount', { count: page.totalElements }) }}
      </p>
      <div class="grid grid-cols-1 gap-3 sm:grid-cols-2">
        <EventCard v-for="event in page.content" :key="event.slug" :event="event" />
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
