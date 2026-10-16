<script lang="ts" setup>
/**
 * The "near me" controls once an origin is chosen, shared by the events map and the venues map:
 * the origin's name, the radius group, and the way back out. It renders no wrapper element,
 * because each map already places these beside controls of its own.
 */
import { X } from '@lucide/vue'
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { Button } from '@/components/ui/button'
import type { Origin } from '@/composables/useLocation'
import { formatDistance } from '@/lib/geo'
import { RADII } from '@/lib/mapPins'
import { describeOrigin } from '@/lib/nearOrigin'

const props = defineProps<{
  origin: Origin
  radiusKm: number
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
  <div :aria-label="t('map.near.radius')" class="flex gap-1" role="group">
    <Button
      v-for="radius in RADII"
      :key="radius"
      :aria-pressed="radius === props.radiusKm"
      :variant="radius === props.radiusKm ? 'default' : 'outline'"
      size="sm"
      type="button"
      @click="emit('select', radius)"
    >
      {{ distance(radius) }}
    </Button>
  </div>
  <Button size="sm" type="button" variant="ghost" @click="emit('clear')">
    <X aria-hidden="true" />
    {{ t('map.near.clear') }}
  </Button>
</template>
