<script setup lang="ts">
/**
 * The saved searches as links beside the Following page's filters (#4099), so a search runs in one
 * press. Renaming and deleting stay on the follows tab, in `SavedSearchList`. At most six show, then
 * one link to that tab: twenty would push the events off a phone's first screen. A long name is cut
 * on screen only; the title and a screen reader keep it whole.
 */
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { RouterLink } from 'vue-router'
import { Button } from '@/components/ui/button'
import type { SavedSearch } from '@/api/account'
import { useLocalePath } from '@/composables/useLocalePath'
import { listQuery } from '@/lib/savedSearch'

const props = defineProps<{ searches: SavedSearch[]; counts: Record<string, number> }>()

const SHOWN = 6

const { t } = useI18n()
const localePath = useLocalePath()

const shown = computed(() => props.searches.slice(0, SHOWN))
</script>

<template>
  <ul
    v-if="searches.length"
    :aria-label="t('account.search.chips')"
    class="flex flex-wrap items-center gap-2"
  >
    <li v-for="search in shown" :key="search.id" class="max-w-full">
      <Button as-child class="max-w-full" size="sm" variant="outline">
        <RouterLink
          :title="search.name"
          :to="{ path: localePath('/events'), query: listQuery(search.query) }"
        >
          <span class="max-w-64 truncate">{{ search.name }}</span>
          <template v-if="counts[search.id] !== undefined">
            <span aria-hidden="true" class="text-muted-foreground">{{ counts[search.id] }}</span>
            <span class="sr-only"
              >, {{ t('account.search.upcoming', { count: counts[search.id] }) }}</span
            >
          </template>
        </RouterLink>
      </Button>
    </li>
    <li v-if="searches.length > SHOWN">
      <Button as-child size="sm" variant="ghost">
        <RouterLink :to="{ path: localePath('/following'), query: { tab: 'follows' } }">
          {{ t('account.search.all', { count: searches.length }) }}
        </RouterLink>
      </Button>
    </li>
  </ul>
</template>
