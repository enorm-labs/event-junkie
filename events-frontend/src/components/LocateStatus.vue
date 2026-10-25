<script lang="ts" setup>
/**
 * What the "near me" button is doing, shared by the events map and the venues map. The failure
 * messages come from each page's own keys, because each offers that page's way round it.
 */
import { useI18n } from 'vue-i18n'
import type { LocateState } from '@/composables/useLocation'

defineProps<{
  state: LocateState
  messages: 'map.near' | 'venues.near'
}>()

const { t } = useI18n()
</script>

<template>
  <p v-if="state === 'locating'" aria-live="polite" class="text-body text-muted-foreground">
    {{ t('map.near.locating') }}
  </p>
  <p
    v-else-if="state === 'denied' || state === 'unavailable'"
    aria-live="polite"
    class="text-body text-muted-foreground"
  >
    {{ t(`${messages}.${state}`) }}
  </p>
</template>
