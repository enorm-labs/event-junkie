<script lang="ts" setup>
/**
 * The "near me" controls once an origin is chosen, shared by the events map, the venues map and the
 * Christmas markets: the origin's name, the radius group, and the way back out. A page that only
 * sorts by distance passes no radius and gets no radius group. It renders no wrapper element,
 * because each map already places these beside controls of its own.
 */
import { X } from '@lucide/vue'
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import ToggleButton from '@/components/ToggleButton.vue'
import { Button } from '@/components/ui/button'
import type { Origin } from '@/composables/useLocation'
import { formatDistance } from '@/lib/geo'
import { RADII } from '@/lib/mapPins'
import { describeOrigin } from '@/lib/nearOrigin'

const props = defineProps<{
  origin: Origin
  radiusKm?: number
}>()

const emit = defineEmits<{
  select: [radius: number]
  clear: []
}>()

const { t, locale } = useI18n()

const originLabel = computed(() => describeOrigin(props.origin, t))

function distance(km: number): string {
  return formatDistance(km, locale.value)
}
</script>

<template>
  <span>{{ t('map.near.from', { origin: originLabel }) }}</span>
  <div v-if="props.radiusKm" :aria-label="t('map.near.radius')" class="flex gap-1" role="group">
    <ToggleButton
      v-for="radius in RADII"
      :key="radius"
      :pressed="radius === props.radiusKm"
      size="sm"
      @click="emit('select', radius)"
    >
      {{ distance(radius) }}
    </ToggleButton>
  </div>
  <Button size="sm" type="button" variant="ghost" @click="emit('clear')">
    <X aria-hidden="true" />
    {{ t('map.near.clear') }}
  </Button>
</template>
