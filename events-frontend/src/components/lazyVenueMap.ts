import { defineAsyncComponent } from 'vue'

import VenueMapPlaceholder from '@/components/VenueMapPlaceholder.vue'

/**
 * MapLibre is the heaviest dependency the site has, so only the routes with a map load it. The placeholder
 * holds the map's height until it arrives, so nothing under the map jumps (#5026).
 */
export const LazyVenueMap = defineAsyncComponent({
  loader: () => import('@/components/VenueMap.vue'),
  loadingComponent: VenueMapPlaceholder,
  delay: 0,
})
