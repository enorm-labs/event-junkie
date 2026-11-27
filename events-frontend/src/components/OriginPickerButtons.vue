<script lang="ts" setup>
/**
 * The device and pick-on-map buttons of the "near me" controls, shared by the events map and the Christmas markets.
 * It renders no wrapper element, because each page places them beside a venue select of its own.
 */
import { LocateFixed, MapPin as MapPinIcon } from '@lucide/vue'
import { useI18n } from 'vue-i18n'
import ToggleButton from '@/components/ToggleButton.vue'
import { Button } from '@/components/ui/button'
import type { LocateState } from '@/composables/useLocation'

defineProps<{
  state: LocateState
  mapUnavailable: boolean
}>()

const picking = defineModel<boolean>('picking', { required: true })

const emit = defineEmits<{
  device: []
}>()

const { t } = useI18n()
</script>

<template>
  <Button
    :disabled="state === 'locating'"
    size="sm"
    type="button"
    variant="outline"
    @click="emit('device')"
  >
    <LocateFixed aria-hidden="true" />
    {{ t('map.near.useLocation') }}
  </Button>
  <ToggleButton v-if="!mapUnavailable" :pressed="picking" size="sm" @click="picking = !picking">
    <MapPinIcon aria-hidden="true" />
    {{ t('map.near.pickOnMap') }}
  </ToggleButton>
</template>
