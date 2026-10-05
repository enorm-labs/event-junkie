<script lang="ts" setup>
/**
 * The one "Clear all" every list page shows beside its filters (#2692), and the same action in an
 * empty state. It reads the URL itself, so a page places it and passes nothing. It renders only
 * while something narrows the list.
 */
import { X } from '@lucide/vue'
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { type LocationQueryRaw, useRoute, useRouter } from 'vue-router'
import { Button } from '@/components/ui/button'
import { MAP_KEYS } from '@/lib/mapPins'

withDefaults(defineProps<{ emptyState?: boolean }>(), { emptyState: false })

/** How the page is shown, not what it shows: clearing keeps them, and they alone do not count. */
const KEPT: readonly string[] = ['sort', 'view', ...MAP_KEYS]

const route = useRoute()
const router = useRouter()
const { t } = useI18n()

const isFiltered = computed(() =>
  Object.keys(route.query).some((key) => key !== 'page' && !KEPT.includes(key)),
)

function clearAll() {
  const query: LocationQueryRaw = {}
  for (const key of KEPT) if (route.query[key] !== undefined) query[key] = route.query[key]
  router.push({ query })
}
</script>

<template>
  <template v-if="isFiltered">
    <Button v-if="emptyState" type="button" variant="outline" @click="clearAll">
      {{ t('common.actions.clearAllFilters') }}
    </Button>
    <Button v-else type="button" variant="ghost" @click="clearAll">
      <X aria-hidden="true" />
      {{ t('common.actions.clearAll') }}
    </Button>
  </template>
</template>
