<script lang="ts" setup>
import type {
  CalendarOptions,
  DatesSetInfo,
  EventClickInfo,
  EventDisplayInfo,
  EventInput,
  MountInfo,
} from '@fullcalendar/vue3'
import FullCalendar from '@fullcalendar/vue3'
import { computed } from 'vue'
import { eventLabel } from '@/lib/format'
// v7 ships plugins as subpaths of the framework package; the standalone
// @fullcalendar/daygrid et al. have no v7 release.
import classicThemePlugin from '@fullcalendar/vue3/themes/classic'
import dayGridPlugin from '@fullcalendar/vue3/daygrid'
import timeGridPlugin from '@fullcalendar/vue3/timegrid'
import listPlugin from '@fullcalendar/vue3/list'
// v7 no longer bundles its own CSS — the skeleton (structure), the theme (rules) and the
// palette (colours) are all opt-in. The palette is imported for its non-colour defaults
// (dot widths, background-event opacities); every colour it defines is overridden in
// <style> below against our design tokens.
import '@fullcalendar/vue3/skeleton.css'
import '@fullcalendar/vue3/themes/classic/theme.css'
import '@fullcalendar/vue3/themes/classic/palette.css'

interface Props {
  events: EventInput[]
  initialView?: string
}

const props = withDefaults(defineProps<Props>(), {
  initialView: 'dayGridMonth',
})

const emit = defineEmits<{
  // Emitted whenever the visible range changes (initial render + navigation), so the parent
  // can fetch events for the new window. Dates are inclusive ISO date strings.
  datesSet: [range: { from: string; to: string }]
  // Emitted when an event is clicked, carrying its slug for router navigation.
  eventClick: [slug: string]
}>()

function toIsoDate(date: Date): string {
  const year = date.getFullYear()
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  return `${year}-${month}-${day}`
}

function handleDatesSet(arg: DatesSetInfo) {
  // FullCalendar's `end` is exclusive; the BFF's `to` is inclusive, so step back a day.
  const inclusiveEnd = new Date(arg.end)
  inclusiveEnd.setDate(inclusiveEnd.getDate() - 1)
  emit('datesSet', { from: toIsoDate(arg.start), to: toIsoDate(inclusiveEnd) })
}

function handleEventClick(arg: EventClickInfo) {
  arg.jsEvent.preventDefault() // navigate via vue-router instead of following event.url
  const slug = arg.event.extendedProps.slug as string | undefined
  if (slug) emit('eventClick', slug)
}

/**
 * A month cell is far narrower than most event titles, so the visible text is clipped. Expose
 * the full label as the element's native `title` tooltip, which is what FullCalendar recommends
 * for this: no extra dependency, and no floating element to fight the cells' clipping. The
 * label itself is shared with EventCard via {@link eventLabel} so the two read identically.
 */
function handleEventDidMount(arg: MountInfo<EventDisplayInfo>) {
  arg.el.title = eventLabel(arg.event.title, arg.event.extendedProps.venue as string | undefined)
}

// FullCalendar is encapsulated here so the rest of the app sees a single, on-theme
// component (see ADR-011). The CSS-variable bridge to our shadcn tokens lives in <style> below.
const options = computed<CalendarOptions>(() => ({
  plugins: [classicThemePlugin, dayGridPlugin, timeGridPlugin, listPlugin],
  initialView: props.initialView,
  headerToolbar: {
    start: 'prev,next today',
    center: 'title',
    end: 'dayGridMonth,timeGridWeek,listWeek',
  },
  // v7 has no `buttonText` option and no built-in English labels behind it.
  // A view button with no resolvable text is not rendered at all — silently, with no warning —
  // so without this block the calendar loses its entire view switcher while still looking fine.
  buttons: {
    dayGridMonth: { text: 'Month' },
    timeGridWeek: { text: 'Week' },
    listWeek: { text: 'List' },
  },
  height: 'auto',
  firstDay: 1, // Monday (Berlin / EU convention)
  nowIndicator: true,
  events: props.events,
  datesSet: handleDatesSet,
  eventClick: handleEventClick,
  eventDidMount: handleEventDidMount,
}))
</script>

<template>
  <div class="event-calendar">
    <FullCalendar :options="options" />
  </div>
</template>

<style scoped>
/* Bridge FullCalendar's CSS variables to our shadcn/Tailwind design tokens. */
/* These custom properties inherit into FullCalendar's DOM below this wrapper, */
/* so the calendar follows light/dark mode automatically. */
/*                                                                             */
/* v7 renamed every one of these and namespaced them per theme, so the whole   */
/* block is keyed to the `classic` theme (--fc-classic-*); the v6 names        */
/* (--fc-border-color, --fc-event-bg-color, …) no longer exist anywhere in the */
/* package. Switching themes means renaming this block to match.               */
/*                                                                             */
/* Every colour the shipped palette varies between light and dark is           */
/* overridden here, deliberately and exhaustively: the palette flips on        */
/* [data-color-scheme=dark], which this app never sets (it toggles a `.dark`   */
/* class), so an un-overridden colour would keep its light value in dark mode. */
/* Mapping to our tokens sidesteps that entirely — they already flip.          */
.event-calendar {
  /* buttons */
  --fc-classic-button: var(--primary);
  --fc-classic-button-border: var(--primary);
  /* The pressed/active shade. Mixing toward `--foreground` rather than a literal `black` is
     deliberate: `--primary-foreground` is near-black in dark mode and near-white in light, so a
     fixed darkening moved the *background* toward the text colour in dark mode and dropped the
     active view button ("Month") to 3.0:1 — a WCAG 1.4.3 failure the axe sweep caught.
     `--foreground` is always the opposite end from `--primary-foreground`, so this shifts away
     from the text in both themes and contrast can only improve. Do not put `black` back. */
  --fc-classic-button-strong: color-mix(in oklch, var(--primary) 80%, var(--foreground));
  --fc-classic-button-strong-border: color-mix(in oklch, var(--primary) 80%, var(--foreground));
  --fc-classic-button-outline: var(--ring);
  --fc-classic-button-foreground: var(--primary-foreground);

  /* primary — events inherit from these by default */
  --fc-classic-primary: var(--primary);
  --fc-classic-primary-foreground: var(--primary-foreground);
  --fc-classic-event: var(--primary);
  --fc-classic-event-contrast: var(--primary-foreground);

  /* calendar content */
  --fc-classic-highlight: color-mix(in oklch, var(--primary) 12%, transparent);
  --fc-classic-today: color-mix(in oklch, var(--primary) 8%, transparent);
  --fc-classic-now: var(--destructive);

  /* neutral backgrounds */
  --fc-classic-background: var(--background);
  --fc-classic-faint: color-mix(in oklch, var(--muted) 50%, transparent);
  --fc-classic-muted: var(--muted);
  --fc-classic-strong: var(--accent);

  /* neutral foregrounds */
  --fc-classic-foreground: var(--foreground);
  /* `faint-foreground` styles the day numbers of adjacent months. It used to fade
     `muted-foreground` to 70% alpha, which put it under 4.5:1 and failed WCAG 1.4.3 — caught by
     the axe sweep in e2e/a11y.spec.ts. "Faint" is carried by the cell's `--fc-classic-faint`
     background instead; dimming the text below the contrast threshold is the one way not to do
     it. Do not reintroduce an alpha here. */
  --fc-classic-faint-foreground: var(--muted-foreground);
  --fc-classic-muted-foreground: var(--muted-foreground);

  /* neutral borders */
  --fc-classic-border: var(--border);
  --fc-classic-strong-border: color-mix(in oklch, var(--border) 70%, var(--foreground));

  color: var(--foreground);
}
</style>
