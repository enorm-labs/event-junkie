<script lang="ts" setup>
import { computed, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { RouterLink, useRoute } from 'vue-router'
import BaseInput from '@/components/BaseInput.vue'
import EventRow from '@/components/EventRow.vue'
import SectionLabel from '@/components/SectionLabel.vue'
import VenueRow from '@/components/VenueRow.vue'
import { Button } from '@/components/ui/button'
import { useAsync } from '@/composables/useAsync'
import { fetchSearch, MIN_SEARCH_LENGTH } from '@/composables/useGlobalSearch'
import { useLocalePath } from '@/composables/useLocalePath'
import { useSearchDraft } from '@/composables/useSearchDraft'
import { CARD_LIST_CLASS, PANEL_CLASS } from '@/lib/utils'

/** Enough to scan; a longer list belongs to the kind's own page, which pages and filters. */
const LIMIT = 20

const { t } = useI18n()
const route = useRoute()
const localePath = useLocalePath()

const q = computed(() => (typeof route.query.q === 'string' ? route.query.q.trim() : ''))
const searchable = computed(() => q.value.length >= MIN_SEARCH_LENGTH)

const { data, error, loading, run } = useAsync(
  () => fetchSearch(q.value, LIMIT),
  'errors.subject.search',
  () => `/api/search?${q.value}`,
)

const { search: draft, applySearch } = useSearchDraft()
watch(
  q,
  () => {
    if (searchable.value) run()
  },
  { immediate: true },
)

const listQuery = computed(() => `?q=${encodeURIComponent(q.value)}`)

interface NamedLink {
  slug?: string
  name?: string
}

// Artists have no list page, so their section never links on; the others link to their own list.
const sections = computed(() => {
  const r = data.value
  if (!r) return []
  return [
    { key: 'venues', group: r.venues, more: `/venues${listQuery.value}` },
    { key: 'events', group: r.events, more: `/events${listQuery.value}` },
    { key: 'artists', group: r.artists, more: undefined },
    { key: 'promoters', group: r.promoters, more: `/promoters${listQuery.value}` },
  ]
    .map(({ key, group, more }) => ({ key, more, ...countOf(group) }))
    .filter((section) => section.total > 0)
})

// The BFF stops counting at 100 (#2533), so a capped total reads "100+".
function countOf(group?: { total?: number; totalCapped?: boolean }) {
  const total = group?.total ?? 0
  return { total, shown: group?.totalCapped ? `${total}+` : String(total) }
}

function linksOf(key: string): { to: string; name: string }[] {
  const items: NamedLink[] =
    key === 'artists' ? (data.value?.artists?.items ?? []) : (data.value?.promoters?.items ?? [])
  return items.map((item) => ({ to: localePath(`/${key}/${item.slug}`), name: item.name ?? '' }))
}
</script>

<template>
  <main class="mx-auto max-w-5xl space-y-6 p-4 sm:p-8">
    <header class="space-y-1">
      <h1 class="text-page font-bold tracking-tight">{{ t('pageTitle.search') }}</h1>
      <p v-if="searchable" class="text-muted-foreground">{{ t('search.resultsFor', { q }) }}</p>
    </header>

    <div :class="PANEL_CLASS">
      <form class="w-full" role="search" @submit.prevent="applySearch">
        <BaseInput
          v-model="draft"
          :aria-label="t('search.label')"
          :placeholder="t('search.placeholder')"
          class="w-full px-3"
          type="search"
        />
      </form>
    </div>

    <p v-if="!searchable" class="text-body text-muted-foreground">{{ t('search.hint') }}</p>
    <p v-else-if="loading" class="text-body text-muted-foreground">{{ t('search.loading') }}</p>
    <p v-else-if="error" class="text-body text-destructive">{{ error }}</p>
    <!-- An empty result offers a control, not only a sentence (#1266). -->
    <div v-else-if="data && !sections.length" class="space-y-3">
      <p class="text-body text-muted-foreground">{{ t('search.empty', { q }) }}</p>
      <Button as-child variant="outline">
        <RouterLink :to="localePath('/events')">{{ t('common.actions.browseEvents') }}</RouterLink>
      </Button>
    </div>
    <template v-else>
      <section v-for="section in sections" :key="section.key" class="space-y-3">
        <div class="flex items-baseline justify-between gap-3">
          <SectionLabel>
            {{ t(`search.groups.${section.key}`) }}
            <span class="text-muted-foreground tabular-nums">{{ section.shown }}</span>
          </SectionLabel>
          <RouterLink
            v-if="section.more && section.total > LIMIT"
            :to="localePath(section.more)"
            class="text-body text-muted-foreground hover:text-foreground"
          >
            {{ t('search.showAll', { count: section.shown }) }}
          </RouterLink>
        </div>
        <ul :class="CARD_LIST_CLASS">
          <template v-if="section.key === 'venues'">
            <li v-for="venue in data?.venues?.items" :key="venue.slug">
              <VenueRow :venue="venue" as="h3" />
            </li>
          </template>
          <template v-else-if="section.key === 'events'">
            <li v-for="event in data?.events?.items" :key="event.slug">
              <EventRow :event="event" as="h3" />
            </li>
          </template>
          <template v-else>
            <li v-for="link in linksOf(section.key)" :key="link.to">
              <RouterLink :to="link.to" class="group block py-3">
                <h3
                  class="truncate text-body font-medium group-hover:underline group-hover:underline-offset-4"
                >
                  {{ link.name }}
                </h3>
              </RouterLink>
            </li>
          </template>
        </ul>
      </section>
    </template>
  </main>
</template>
