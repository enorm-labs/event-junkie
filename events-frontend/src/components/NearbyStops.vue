<script lang="ts" setup>
import { useI18n } from 'vue-i18n'
import type { NearbyStop } from '@/api/types'

defineProps<{ stops: readonly NearbyStop[] | null | undefined }>()

const { t, n } = useI18n()
</script>

<template>
  <!-- From our own copy of VBB's stop data (#2724): the page asks no third party. -->
  <ul
    v-if="stops?.length"
    :aria-label="t('detail.venue.nearbyStops')"
    class="space-y-0.5 text-body"
    data-testid="nearby-stops"
  >
    <li v-for="stop in stops" :key="stop.name">
      {{ stop.name
      }}<span class="text-muted-foreground"
        ><template v-if="stop.lines?.length">{{ ` · ${stop.lines.join(', ')}` }}</template
        >{{ ' · '
        }}<span class="whitespace-nowrap">{{
          t('detail.venue.walk', { metres: n(stop.walkingMetres ?? 0) })
        }}</span></span
      >
    </li>
  </ul>
</template>
