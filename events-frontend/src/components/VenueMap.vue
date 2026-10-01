<script lang="ts" setup>
/**
 * A map of Berlin with one marker per pin. It knows venues and coordinates, not events: the view
 * decides what a pin says, and handles a selection through `v-model:selected`.
 *
 * Everything it loads is served from this site (`scripts/map-assets.sh`). A tile server elsewhere
 * would receive every visitor's address, and the privacy notice says no third party does.
 */
import 'maplibre-gl/dist/maplibre-gl.css'
import { Minus, Plus } from '@lucide/vue'
import { layers, namedFlavor } from '@protomaps/basemaps'
import {
  addProtocol,
  AttributionControl,
  LngLatBounds,
  Map as MapLibreMap,
  Marker,
  setWorkerUrl,
  type StyleSpecification,
} from 'maplibre-gl'
import workerUrl from 'maplibre-gl/dist/maplibre-gl-worker.mjs?worker&url'
import { Protocol } from 'pmtiles'
import { markRaw, onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { Button } from '@/components/ui/button'
import type { MapPin } from '@/lib/mapPins'

const props = defineProps<{ pins: MapPin[] }>()
const selected = defineModel<string | null>('selected', { default: null })
const emit = defineEmits<{ unavailable: [] }>()

/** The importer's validation range (`VenueRequest.kt`), which is also the area the tiles cover. */
const BERLIN: [[number, number], [number, number]] = [
  [13.05, 52.3],
  [13.8, 52.7],
]
const MAX_FIT_ZOOM = 15
const ATTRIBUTION =
  '<a href="https://www.openstreetmap.org/copyright">© OpenStreetMap</a> · <a href="https://protomaps.com">Protomaps</a>'

const { t, locale } = useI18n()
const container = ref<HTMLElement | null>(null)
const map = shallowRef<MapLibreMap | null>(null)
const markers = new Map<string, { marker: Marker; element: HTMLButtonElement }>()

// A same-origin worker file: the site's CSP has no `blob:`, which MapLibre's default worker needs.
let protocolRegistered = false
function registerOnce() {
  if (protocolRegistered) return
  setWorkerUrl(workerUrl)
  addProtocol('pmtiles', new Protocol().tile)
  protocolRegistered = true
}

function isDark(): boolean {
  return document.documentElement.classList.contains('dark')
}

function style(): StyleSpecification {
  const base = new URL(`${import.meta.env.BASE_URL}map/`, window.location.origin).href
  const flavor = isDark() ? 'dark' : 'light'
  return {
    version: 8,
    glyphs: `${base}fonts/{fontstack}/{range}.pbf`,
    sprite: `${base}sprites/${flavor}`,
    sources: {
      protomaps: {
        type: 'vector',
        url: `pmtiles://${base}berlin.pmtiles`,
        attribution: ATTRIBUTION,
      },
    },
    layers: layers('protomaps', namedFlavor(flavor), { lang: locale.value }),
  }
}

const MARKER_CLASS =
  'flex cursor-pointer items-center justify-center rounded-full border-2 border-background text-meta font-semibold outline-none focus-visible:ring-3 focus-visible:ring-ring/50'
/** A pin with a count needs room for it; a bare pin is a dot, so 90 venues do not bury the map. */
const BADGE_SIZE_CLASS = 'h-7 min-w-7 px-1.5'
const DOT_SIZE_CLASS = 'size-4'

const SELECTED_CLASSES = ['bg-foreground', 'text-background']
const UNSELECTED_CLASSES = ['bg-primary', 'text-primary-foreground']

// classList, never className: MapLibre positions a marker through classes of its own on the element.
function styleMarker(element: HTMLButtonElement, slug: string) {
  const isSelected = slug === selected.value
  element.classList.remove(...(isSelected ? UNSELECTED_CLASSES : SELECTED_CLASSES))
  element.classList.add(...(isSelected ? SELECTED_CLASSES : UNSELECTED_CLASSES))
  element.setAttribute('aria-pressed', String(isSelected))
}

function renderMarkers() {
  const instance = map.value
  if (!instance) return
  for (const { marker } of markers.values()) marker.remove()
  markers.clear()
  for (const pin of props.pins) {
    const element = document.createElement('button')
    element.type = 'button'
    element.className = `${MARKER_CLASS} ${pin.badge ? BADGE_SIZE_CLASS : DOT_SIZE_CLASS}`
    styleMarker(element, pin.slug)
    element.title = pin.label
    element.setAttribute('aria-label', pin.label)
    element.textContent = pin.badge ?? ''
    element.addEventListener('click', (event) => {
      event.stopPropagation()
      selected.value = selected.value === pin.slug ? null : pin.slug
    })
    const marker = new Marker({ element }).setLngLat([pin.longitude, pin.latitude]).addTo(instance)
    markers.set(pin.slug, { marker, element })
  }
}

function frame() {
  const instance = map.value
  if (!instance || !props.pins.length) return
  const bounds = new LngLatBounds()
  for (const pin of props.pins) bounds.extend([pin.longitude, pin.latitude])
  instance.fitBounds(bounds, { padding: 48, maxZoom: MAX_FIT_ZOOM, animate: false })
}

function restyleSelection() {
  for (const [slug, { element }] of markers) styleMarker(element, slug)
}

// The theme toggle flips a class on <html>, so the basemap follows it without an event bus.
const themeObserver = new MutationObserver(() => map.value?.setStyle(style(), { diff: false }))

onMounted(() => {
  if (!container.value) return
  registerOnce()
  try {
    map.value = markRaw(
      new MapLibreMap({
        container: container.value,
        style: style(),
        bounds: BERLIN,
        maxBounds: [
          [12.9, 52.2],
          [13.95, 52.8],
        ],
        minZoom: 9,
        attributionControl: false,
        cooperativeGestures: true,
        locale: {
          'Map.Title': t('map.label'),
          'CooperativeGesturesHandler.WindowsHelpText': t('map.gestureHelpDesktop'),
          'CooperativeGesturesHandler.MacHelpText': t('map.gestureHelpMac'),
          'CooperativeGesturesHandler.MobileHelpText': t('map.gestureHelpMobile'),
        },
      }),
    )
  } catch {
    // No WebGL: the view offers its list instead.
    emit('unavailable')
    return
  }
  map.value.addControl(new AttributionControl({ compact: false }), 'bottom-right')
  map.value.on('click', () => {
    selected.value = null
  })
  renderMarkers()
  frame()
  themeObserver.observe(document.documentElement, { attributes: true, attributeFilter: ['class'] })
})

watch(
  () => props.pins,
  () => {
    renderMarkers()
    frame()
  },
)
watch(selected, restyleSelection)
watch(locale, () => map.value?.setStyle(style(), { diff: false }))

onBeforeUnmount(() => {
  themeObserver.disconnect()
  map.value?.remove()
  markers.clear()
})
</script>

<template>
  <!-- Our own zoom buttons: MapLibre's draw their icons from `data:` URLs, which the CSP refuses. -->
  <div class="relative -mx-4 sm:mx-0">
    <div
      ref="container"
      class="venue-map h-112 overflow-hidden border-y border-border sm:rounded-lg sm:border-x lg:h-144"
    />
    <div class="absolute top-3 right-3 flex flex-col gap-1">
      <Button
        :aria-label="t('map.zoomIn')"
        :title="t('map.zoomIn')"
        size="icon-sm"
        variant="outline"
        @click="map?.zoomIn()"
      >
        <Plus aria-hidden="true" />
      </Button>
      <Button
        :aria-label="t('map.zoomOut')"
        :title="t('map.zoomOut')"
        size="icon-sm"
        variant="outline"
        @click="map?.zoomOut()"
      >
        <Minus aria-hidden="true" />
      </Button>
    </div>
  </div>
</template>

<style scoped>
/* MapLibre's attribution bar is white on every theme; this ties it to ours. */
.venue-map :deep(.maplibregl-ctrl-attrib) {
  background-color: color-mix(in oklch, var(--background) 80%, transparent);
  color: var(--muted-foreground);
}

.venue-map :deep(.maplibregl-ctrl-attrib a) {
  color: inherit;
}
</style>
