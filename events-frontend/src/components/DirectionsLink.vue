<script lang="ts" setup>
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import type { VenueSummary } from '@/api/types'
import { clientMapsPlatform, directionsUrl, type MapsPlatform } from '@/lib/directions'
import { venuePosition } from '@/lib/mapPins'

const props = defineProps<{ venue: VenueSummary | null | undefined }>()

const { t } = useI18n()

// The web link renders first and stays for a visitor without JavaScript; the platform's own link
// replaces it once mounted (#2767).
const platform = ref<MapsPlatform>('web')
onMounted(() => {
  platform.value = clientMapsPlatform()
})

const href = computed(() => {
  const position = venuePosition(props.venue ?? undefined)
  return position ? directionsUrl(position, props.venue?.name, platform.value) : null
})
// A `geo:` link hands over to an app; in a new tab it would leave an empty tab behind.
const newTab = computed(() => href.value?.startsWith('https:') ?? false)
</script>

<template>
  <a
    v-if="href"
    :href="href"
    :rel="newTab ? 'noopener noreferrer' : undefined"
    :target="newTab ? '_blank' : undefined"
    class="text-primary underline-offset-4 hover:underline"
    data-testid="venue-directions-link"
    >{{ t('detail.venue.directions')
    }}<span class="sr-only">{{ ` ${t('detail.venue.directionsLeaves')}` }}</span></a
  >
</template>
