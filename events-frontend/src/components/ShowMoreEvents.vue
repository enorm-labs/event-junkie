<script lang="ts" setup>
import { type LocationQueryRaw, RouterLink, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { Button } from '@/components/ui/button'

/**
 * "Show more events" under the events list, which appends the next page instead of replacing it:
 * nobody looks for page 187 of 376, and the date filter is how a visitor moves far.
 *
 * It is a link to `next`, so a crawler follows it and a new tab opens there. A plain click replaces
 * the history entry, so back leaves the list instead of stepping through every load, and keeps the
 * scroll position, which the router otherwise resets on a navigation.
 */
defineProps<{ next: { query: LocationQueryRaw }; shown: number; total: number; loading: boolean }>()

const router = useRouter()
const { t, n } = useI18n()

function showMore(event: MouseEvent, next: { query: LocationQueryRaw }) {
  if (event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return
  event.preventDefault()
  router.replace({ ...next, state: { keepScroll: true } })
}
</script>

<template>
  <div class="flex flex-col items-center gap-2 pt-2">
    <RouterLink v-slot="{ href }" custom :to="next">
      <Button as-child :disabled="loading" variant="outline">
        <a :aria-disabled="loading || undefined" :href="href" @click="showMore($event, next)">
          {{ loading ? t('common.states.loading') : t('common.pagination.loadMore') }}
        </a>
      </Button>
    </RouterLink>
    <p class="text-meta text-muted-foreground tabular-nums">
      {{ t('common.pagination.shownOf', { shown: n(shown), total: n(total) }) }}
    </p>
  </div>
</template>
