<script lang="ts" setup>
/**
 * A map of Berlin with one marker per pin. It knows venues and coordinates, not events: the view
 * decides what a pin says, and handles a selection through `v-model:selected`. Pins that would
 * overlap on screen share one marker, which opens a list of its venues.
 *
 * Everything it loads is served from this site (`scripts/map-assets.sh`). A tile server elsewhere
 * would receive every visitor's address, and the privacy notice says no third party does.
 */
import 'maplibre-gl/dist/maplibre-gl.css'
import { Minus, Plus, X } from '@lucide/vue'
import { layers, namedFlavor } from '@protomaps/basemaps'
import {
  addProtocol,
  AttributionControl,
  type GeoJSONSource,
  LngLatBounds,
  Map as MapLibreMap,
  Marker,
  setWorkerUrl,
  type StyleSpecification,
} from 'maplibre-gl'
import workerUrl from 'maplibre-gl/dist/maplibre-gl-worker.mjs?worker&url'
import { Protocol } from 'pmtiles'
import {
  computed,
  markRaw,
  nextTick,
  onBeforeUnmount,
  onMounted,
  ref,
  shallowRef,
  watch,
} from 'vue'
import { useI18n } from 'vue-i18n'
import { Button } from '@/components/ui/button'
import { circlePolygon, type Position } from '@/lib/geo'
import { type MapPin, overlapGroups } from '@/lib/mapPins'
import { CARD_LIST_CLASS, MAP_PANEL_CLASS } from '@/lib/utils'

const props = defineProps<{
  pins: MapPin[]
  /** Where "near" is measured from: drawn as a marker, with the radius around it. */
  origin?: Position | null
  radiusKm?: number | null
  /** The next tap on the map sets the origin instead of clearing the selection. */
  picking?: boolean
  /** Where the map opens centred, for a link from one venue's page. A radius circle wins. */
  focus?: Position | null
}>()
const selected = defineModel<string | null>('selected', { default: null })
const emit = defineEmits<{ unavailable: []; pick: [Position] }>()

/** The importer's validation range (`VenueRequest.kt`), which is also the area the tiles cover. */
const BERLIN: [[number, number], [number, number]] = [
  [13.05, 52.3],
  [13.8, 52.7],
]
const MAX_FIT_ZOOM = 15
/** Centres closer than a two-digit badge is wide draw one marker over another. */
const OVERLAP_PX = 36
const ATTRIBUTION =
  '<a href="https://www.openstreetmap.org/copyright">© OpenStreetMap</a> · <a href="https://protomaps.com">Protomaps</a>'

const { t, locale } = useI18n()
const container = ref<HTMLElement | null>(null)
const overlay = ref<HTMLElement | null>(null)
const map = shallowRef<MapLibreMap | null>(null)
/** One marker on the map: a single pin, or the pins that would overlap it at this zoom. */
interface Placed {
  marker: Marker
  element: HTMLButtonElement
  pins: MapPin[]
}
/** By the slugs a marker holds, so a regroup on zoom keeps the markers that did not change. */
const placed = new Map<string, Placed>()
const placedBySlug = new Map<string, Placed>()
/** The pins of the group marker whose list is open. */
const group = shallowRef<MapPin[] | null>(null)
const groupKey = computed(() => (group.value ? keyOf(group.value) : null))
let originMarker: Marker | null = null
let nameMarker: Marker | null = null

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

// Focus is an outline: a `ring-*` is a box-shadow, which a live pin's pulse overrides (#2664).
// `outline-solid` is needed because `outline-none` leaves `outline-2` reading a `none` style.
const MARKER_CLASS =
  'flex cursor-pointer items-center justify-center border-2 border-background font-semibold outline-none focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-ring focus-visible:outline-solid'
/**
 * A pin with a count needs room for it; an empty badge is a plain disc still large enough to tap;
 * a pin without a badge is a dot, so 90 venues do not bury the map. The selected pin grows a step,
 * so it reads as chosen without its colour (#2347).
 */
const SIZE_CLASSES = {
  count: {
    rest: ['h-7', 'min-w-7', 'px-1.5', 'text-meta'],
    selected: ['h-9', 'min-w-9', 'px-2', 'text-body'],
  },
  disc: { rest: ['size-6'], selected: ['size-8'] },
  dot: { rest: ['size-4'], selected: ['size-6'] },
}
/** A group marker is square-cornered, so its number does not read as one venue's event count. */
const SHAPE_CLASSES = { single: 'rounded-full', group: 'rounded-md' }
/** The selected venue's name beside its pin; the panel says it to a screen reader. */
const NAME_CLASS =
  'pointer-events-none rounded-sm bg-background/90 px-1.5 text-meta font-semibold whitespace-nowrap text-foreground'

const DIMMED_CLASS = 'opacity-40'
/** The pin itself pulses: a corner dot in the pin's own colour was easy to miss. */
const LIVE_CLASS = 'is-live'
const ORIGIN_CLASS =
  'size-4 rounded-full border-2 border-background bg-foreground ring-4 ring-foreground/25'

// On top of its neighbours: in a dense Kreuzberg block the chosen pin was often under another one.
const SELECTED_CLASSES = ['bg-foreground', 'text-background', 'z-10']
const UNSELECTED_CLASSES = ['bg-primary', 'text-primary-foreground']
// A venue with nothing in the range stays grey even while chosen: the accent would read as "on".
const QUIET_CLASSES = { selected: ['bg-muted-foreground', 'z-10'], rest: ['bg-muted-foreground'] }

function keyOf(pins: readonly MapPin[]): string {
  return pins.map((pin) => pin.slug).join('|')
}

function isActive({ pins }: Placed): boolean {
  if (pins.length > 1 && keyOf(pins) === groupKey.value) return true
  return pins.some((pin) => pin.slug === selected.value)
}

// classList, never className: MapLibre positions a marker through classes of its own on the element.
function styleMarker(entry: Placed) {
  const { element, pins } = entry
  const isSelected = isActive(entry)
  const badge = pins[0]?.badge
  const size =
    SIZE_CLASSES[pins.length > 1 || badge ? 'count' : badge === undefined ? 'dot' : 'disc']
  const colour = pins.every((pin) => pin.quiet)
    ? QUIET_CLASSES
    : { selected: SELECTED_CLASSES, rest: UNSELECTED_CLASSES }
  element.classList.remove(
    ...(isSelected ? colour.rest : colour.selected),
    ...(isSelected ? size.rest : size.selected),
  )
  element.classList.add(
    ...(isSelected ? colour.selected : colour.rest),
    ...(isSelected ? size.selected : size.rest),
  )
  element.setAttribute('aria-pressed', String(isSelected))
}

/** Half the selected badge's width plus a gap, so the name starts clear of the pin. */
const NAME_OFFSET: [number, number] = [22, 0]

function renderName() {
  const instance = map.value
  const entry = selected.value ? placedBySlug.get(selected.value) : undefined
  const pin = entry?.pins.find(({ slug }) => slug === selected.value)
  if (!instance || !entry || !pin?.name) {
    nameMarker?.remove()
    nameMarker = null
    return
  }
  if (!nameMarker) {
    const element = document.createElement('div')
    element.className = NAME_CLASS
    element.setAttribute('aria-hidden', 'true')
    nameMarker = new Marker({ element, anchor: 'left', offset: NAME_OFFSET })
  }
  nameMarker.getElement().textContent = pin.name
  nameMarker.setLngLat(entry.marker.getLngLat()).addTo(instance)
}

function place(instance: MapLibreMap, pins: MapPin[]): Placed {
  const [first] = pins
  const isGroup = pins.length > 1
  const element = document.createElement('button')
  element.type = 'button'
  element.className = MARKER_CLASS
  element.classList.add(isGroup ? SHAPE_CLASSES.group : SHAPE_CLASSES.single)
  if (pins.every((pin) => pin.dimmed)) element.classList.add(DIMMED_CLASS)
  if (pins.some((pin) => pin.live)) element.classList.add(LIVE_CLASS)
  const label = isGroup
    ? t('map.groupLabel', {
        count: pins.length,
        venues: pins.map((pin) => pin.label).join('; '),
      })
    : (first?.label ?? '')
  element.title = label
  element.setAttribute('aria-label', label)
  element.textContent = isGroup
    ? String(pins.reduce((sum, pin) => sum + (pin.weight ?? 1), 0))
    : (first?.badge ?? '')
  const longitude = pins.reduce((sum, pin) => sum + pin.longitude, 0) / pins.length
  const latitude = pins.reduce((sum, pin) => sum + pin.latitude, 0) / pins.length
  const marker = new Marker({ element }).setLngLat([longitude, latitude]).addTo(instance)
  const entry = { marker, element, pins }
  element.addEventListener('click', (event) => {
    event.stopPropagation()
    if (isGroup) {
      group.value = keyOf(pins) === groupKey.value ? null : pins
      selected.value = null
    } else {
      group.value = null
      selected.value = selected.value === first?.slug ? null : (first?.slug ?? null)
    }
  })
  styleMarker(entry)
  return entry
}

function clearMarkers() {
  for (const { marker } of placed.values()) marker.remove()
  placed.clear()
  placedBySlug.clear()
}

/**
 * Groups the pins by where they land on screen at this zoom. A rebuild redraws every marker, for new
 * pins; a regroup after a zoom keeps each marker whose pins did not change, and with it the focus.
 */
function renderMarkers(rebuild: boolean) {
  const instance = map.value
  if (!instance) return
  if (rebuild) clearMarkers()
  const points = props.pins.map((pin) => instance.project([pin.longitude, pin.latitude]))
  const next = new Map<string, Placed>()
  for (const indexes of overlapGroups(points, OVERLAP_PX)) {
    const pins = indexes.flatMap((index) => props.pins[index] ?? [])
    const key = keyOf(pins)
    next.set(key, placed.get(key) ?? place(instance, pins))
    placed.delete(key)
  }
  clearMarkers()
  for (const [key, entry] of next) {
    placed.set(key, entry)
    for (const pin of entry.pins) placedBySlug.set(pin.slug, entry)
  }
  renderName()
}

/** The circle when there is one, so "near me" opens on what is near; then the focus; then every pin. */
function frame() {
  const instance = map.value
  if (!instance) return
  const bounds = new LngLatBounds()
  const circle = radiusRing()
  if (!circle && props.focus) {
    instance.jumpTo({ center: [props.focus.longitude, props.focus.latitude], zoom: MAX_FIT_ZOOM })
    return
  }
  if (circle) for (const point of circle) bounds.extend(point)
  else if (props.pins.length)
    for (const pin of props.pins) bounds.extend([pin.longitude, pin.latitude])
  else return
  instance.fitBounds(bounds, { padding: 48, maxZoom: MAX_FIT_ZOOM, animate: false })
}

const RADIUS_SOURCE = 'near-radius'

function radiusRing(): [number, number][] | null {
  return props.origin && props.radiusKm ? circlePolygon(props.origin, props.radiusKm) : null
}

/**
 * A CSS custom property as `rgb()`. The tokens are `oklch()`, which MapLibre's style colours do not
 * parse; a canvas converts any colour the browser knows.
 */
function tokenColor(name: string): string {
  const canvas = document.createElement('canvas').getContext('2d')
  const value = getComputedStyle(document.documentElement).getPropertyValue(name).trim()
  if (!canvas || !value) return 'grey'
  canvas.fillStyle = value
  canvas.fillRect(0, 0, 1, 1)
  const [r, g, b] = canvas.getImageData(0, 0, 1, 1).data
  return `rgb(${r}, ${g}, ${b})`
}

// A style swap (theme, language) drops every added layer, so this runs on each `style.load`.
function addRadiusLayers() {
  const instance = map.value
  if (!instance || instance.getSource(RADIUS_SOURCE)) return
  const color = tokenColor('--primary')
  instance.addSource(RADIUS_SOURCE, { type: 'geojson', data: radiusData() })
  instance.addLayer({
    id: `${RADIUS_SOURCE}-fill`,
    type: 'fill',
    source: RADIUS_SOURCE,
    paint: { 'fill-color': color, 'fill-opacity': 0.08 },
  })
  instance.addLayer({
    id: `${RADIUS_SOURCE}-line`,
    type: 'line',
    source: RADIUS_SOURCE,
    paint: { 'line-color': color, 'line-width': 2, 'line-dasharray': [2, 2] },
  })
}

function radiusData(): Parameters<GeoJSONSource['setData']>[0] {
  const ring = radiusRing()
  return {
    type: 'FeatureCollection',
    features: ring
      ? [{ type: 'Feature', properties: {}, geometry: { type: 'Polygon', coordinates: [ring] } }]
      : [],
  }
}

function renderOrigin() {
  const instance = map.value
  if (!instance) return
  ;(instance.getSource(RADIUS_SOURCE) as GeoJSONSource | undefined)?.setData(radiusData())
  if (!props.origin) {
    originMarker?.remove()
    originMarker = null
    return
  }
  if (!originMarker) {
    const element = document.createElement('div')
    element.className = ORIGIN_CLASS
    element.setAttribute('role', 'img')
    element.setAttribute('aria-label', t('map.near.origin'))
    element.title = t('map.near.origin')
    originMarker = new Marker({ element })
  }
  originMarker.setLngLat([props.origin.longitude, props.origin.latitude]).addTo(instance)
}

/** The map's centre, for choosing an origin without a pointer. */
function center(): Position | null {
  const point = map.value?.getCenter()
  return point ? { latitude: point.lat, longitude: point.lng } : null
}

/** Moves focus to a pin, for a panel that closes under the keyboard. */
function focusPin(slug: string) {
  placedBySlug.get(slug)?.element.focus()
}

defineExpose({ center, focusPin })

function restyleSelection() {
  for (const entry of placed.values()) styleMarker(entry)
  renderName()
}

// The list goes with the click, so focus goes back to the marker that opened it.
function closeGroup() {
  const slug = group.value?.[0]?.slug
  group.value = null
  if (slug) focusPin(slug)
}

/** A venue from a group's list: the view's own panel takes over, and focus waits on the marker. */
function choose(slug: string) {
  group.value = null
  selected.value = slug
  focusPin(slug)
}

/** Room left between a pin and the overlay once the map has moved it clear. */
const REVEAL_MARGIN = 16

/**
 * Pans the selected pin out from under the view's overlay, the shorter way: up on a phone, where
 * the panel spans the map, and right or up beside it. `essential: false` lets MapLibre skip the
 * animation under reduced motion.
 */
async function revealSelection() {
  await nextTick()
  const element = selected.value
    ? placedBySlug.get(selected.value)?.element
    : groupKey.value
      ? placed.get(groupKey.value)?.element
      : undefined
  const panel = overlay.value?.firstElementChild?.getBoundingClientRect()
  if (!map.value || !element || !panel) return
  const pin = element.getBoundingClientRect()
  const covered =
    pin.right > panel.left &&
    pin.left < panel.right &&
    pin.bottom > panel.top &&
    pin.top < panel.bottom
  if (!covered) return
  const up = pin.bottom - panel.top + REVEAL_MARGIN
  const right = panel.right - pin.left + REVEAL_MARGIN
  const width = container.value?.clientWidth ?? 0
  map.value.panBy(right < up && panel.right + pin.width < width ? [-right, 0] : [0, up], {
    essential: false,
  })
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
  map.value.on('click', (event) => {
    if (props.picking) emit('pick', { latitude: event.lngLat.lat, longitude: event.lngLat.lng })
    else {
      selected.value = null
      group.value = null
    }
  })
  map.value.on('style.load', addRadiusLayers)
  map.value.on('zoomend', () => renderMarkers(false))
  renderOrigin()
  frame()
  renderMarkers(true)
  themeObserver.observe(document.documentElement, { attributes: true, attributeFilter: ['class'] })
})

watch(
  () => props.pins,
  (pins) => {
    frame()
    renderMarkers(true)
    // The open list keeps the venues still on the map, under their new pins.
    const slugs = new Set(group.value?.map((pin) => pin.slug))
    const kept = pins.filter((pin) => slugs.has(pin.slug))
    group.value = kept.length > 1 ? kept : null
  },
)
watch(
  () => [props.origin, props.radiusKm, props.focus],
  () => {
    renderOrigin()
    frame()
  },
)
watch([selected, group], () => {
  restyleSelection()
  void revealSelection()
})
watch(locale, () => map.value?.setStyle(style(), { diff: false }))

onBeforeUnmount(() => {
  themeObserver.disconnect()
  map.value?.remove()
  placed.clear()
  placedBySlug.clear()
  originMarker = null
  nameMarker = null
})
</script>

<template>
  <!-- Our own zoom buttons: MapLibre's draw their icons from `data:` URLs, which the CSP refuses. -->
  <div class="relative -mx-4 sm:mx-0">
    <div
      ref="container"
      :class="{ 'is-picking': picking }"
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
    <!-- Whatever the view lays over the map, such as the selected venue's panel. The wrapper is
         static, so its child still positions against the map. -->
    <div ref="overlay">
      <section v-if="group" aria-live="polite" :class="MAP_PANEL_CLASS">
        <div class="flex items-start gap-2">
          <h2 class="min-w-0 flex-1 truncate text-card-title font-bold tracking-tight">
            {{ t('map.groupHeading', { count: group.length }) }}
          </h2>
          <Button
            :aria-label="t('map.closePanel')"
            :title="t('map.closePanel')"
            size="icon-xs"
            type="button"
            variant="ghost"
            @click="closeGroup"
          >
            <X aria-hidden="true" />
          </Button>
        </div>
        <ul :class="CARD_LIST_CLASS">
          <li v-for="pin in group" :key="pin.slug">
            <button
              class="w-full py-2 text-left hover:text-primary"
              type="button"
              @click="choose(pin.slug)"
            >
              <span class="block truncate text-body font-semibold">{{
                pin.name ?? pin.label
              }}</span>
              <span v-if="pin.note" class="block truncate text-meta text-muted-foreground">
                {{ pin.note }}
              </span>
            </button>
          </li>
        </ul>
      </section>
      <slot />
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

/*
 * A live pin pulses through its shadow: outside the box, so a count pill keeps its text and the
 * marker keeps its position. Slow, because a Friday night lights up dozens of pins at once.
 * Without motion a still ring carries the same meaning.
 */
.venue-map :deep(.is-live) {
  box-shadow: 0 0 0 3px color-mix(in oklch, var(--primary) 45%, transparent);
}

@media (prefers-reduced-motion: no-preference) {
  .venue-map :deep(.is-live) {
    animation: live-pulse 2s cubic-bezier(0, 0, 0.2, 1) infinite;
  }
}

/* A wave behind the focus outline pulls the eye away from it; the still ring stays. */
.venue-map :deep(.is-live:focus-visible) {
  animation: none;
}

/* One wave, then a rest: a pause between waves reads calmer than a constant throb. */
@keyframes live-pulse {
  0% {
    box-shadow: 0 0 0 0 color-mix(in oklch, var(--primary) 80%, transparent);
  }
  60%,
  100% {
    box-shadow: 0 0 0 12px color-mix(in oklch, var(--primary) 0%, transparent);
  }
}

/* Picking a point: the cursor says the next tap places something. */
.venue-map.is-picking :deep(.maplibregl-canvas) {
  cursor: crosshair;
}
</style>
