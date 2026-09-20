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
import { useI18n } from 'vue-i18n'
import { berlinTimeIso, eventLabel, todayIso } from '@/lib/format'
// v7 ships plugins as subpaths of the framework package; the standalone
// @fullcalendar/daygrid et al. have no v7 release.
import classicThemePlugin from '@fullcalendar/vue3/themes/classic'
import dayGridPlugin from '@fullcalendar/vue3/daygrid'
import listPlugin from '@fullcalendar/vue3/list'
// v7 no longer bundles its CSS; skeleton, theme and palette are opt-in. The palette is imported
// for its non-colour defaults (dot widths, background-event opacities); every colour it defines
// is overridden in <style> below against our design tokens.
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
  // Emitted whenever the visible range changes, so the parent can fetch the new window. Inclusive
  // ISO date strings.
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
 * A month cell clips most titles, so the full label is the element's native `title` tooltip,
 * FullCalendar's own recommendation; shared with EventCard via {@link eventLabel}.
 */
function handleEventDidMount(arg: MountInfo<EventDisplayInfo>) {
  arg.el.title = eventLabel(arg.event.title, arg.event.extendedProps.venue as string | undefined)
  // The pulse below is colour and motion only; the same words EventCard puts behind its dot make
  // the mark exist for a screen reader (WCAG 1.4.1) and give the e2e suite a handle.
  if (arg.el.classList.contains('fc-event-live')) {
    const label = document.createElement('span')
    label.className = 'sr-only'
    label.textContent = t('events.card.liveTonight')
    arg.el.append(label)
  }
}

const { t } = useI18n()

// FullCalendar is encapsulated here so the rest of the app sees one on-theme component
// (ADR-011). The week is a row of day cells, not a timetable: nearly every event starts between
// 19:00 and 23:00, so a time axis stacks a night into slivers (#1412).
const options = computed<CalendarOptions>(() => ({
  plugins: [classicThemePlugin, dayGridPlugin, listPlugin],
  initialView: props.initialView,
  headerToolbar: {
    start: 'prev,next today',
    center: 'title',
    end: 'dayGridMonth,dayGridWeek,listWeek',
  },
  // v7 has no `buttonText` option and no built-in English labels. A view button with no resolvable
  // text is silently not rendered, so without this block the calendar loses its view switcher.
  buttons: {
    dayGridMonth: { text: t('calendar.view.month') },
    dayGridWeek: { text: t('calendar.view.week') },
    listWeek: { text: t('calendar.view.list') },
  },
  height: 'auto',
  firstDay: 1, // Monday (Berlin / EU convention)
  // Today is Berlin's day, the clock the live dots run on (`todayIso`), not the visitor's.
  now: () => `${todayIso()}T${berlinTimeIso()}:00`,
  dayCellTopInnerClass: (info) => (info.isToday ? 'fc-today-number' : undefined),
  dayHeaderInnerClass: (info) => (info.isToday ? 'fc-today-number' : undefined),
  // A full cell folds into a "+N more" popover. The month takes a count: `true` sizes to the cell,
  // and a phone-width cell fits one event.
  views: { dayGridMonth: { dayMaxEvents: 6 }, dayGridWeek: { dayMaxEvents: true } },
  moreLinkText: t('calendar.more'), // rendered as `+N <more>`
  // A club night ending 06:00 is one day's entry, not a two-day bar; a run still on at 09:00 spans.
  nextDayThreshold: '09:00',
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
/*
 * Bridge FullCalendar's CSS variables to our shadcn/Tailwind design tokens; they inherit into
 * FullCalendar's DOM below this wrapper, so the calendar follows light/dark mode.
 *
 * v7 namespaced every variable per theme, so this block is keyed to `classic` (--fc-classic-*);
 * the v6 names no longer exist. Every colour the shipped palette varies between light and dark is
 * overridden here, exhaustively: the palette flips on [data-color-scheme=dark], which this app
 * never sets (it toggles `.dark`), so an un-overridden colour would keep its light value.
 */
.event-calendar {
  /* buttons */
  --fc-classic-button: var(--primary);
  --fc-classic-button-border: var(--primary);
  /*
   * The pressed shade mixes toward `--foreground`, not a literal `black`: `--primary-foreground` is
   * near-black in dark mode, so a fixed darkening moved the background toward the text and dropped
   * the active view button to 3.0:1, a WCAG 1.4.3 failure axe caught. `--foreground` is always the
   * opposite end, so contrast can only improve. Do not put `black` back.
   */
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
  --fc-classic-today: color-mix(in oklch, var(--primary) 22%, transparent);
  --fc-classic-now: var(--destructive);

  /* neutral backgrounds */
  --fc-classic-background: var(--background);
  --fc-classic-faint: color-mix(in oklch, var(--muted) 50%, transparent);
  --fc-classic-muted: var(--muted);
  --fc-classic-strong: var(--accent);

  /* neutral foregrounds */
  --fc-classic-foreground: var(--foreground);
  /*
   * `faint-foreground` styles adjacent months' day numbers. Fading `muted-foreground` to 70% alpha
   * put it under 4.5:1 (WCAG 1.4.3, caught by e2e/a11y.spec.ts); "faint" is carried by the cell's
   * `--fc-classic-faint` background instead. Do not reintroduce an alpha here.
   */
  --fc-classic-faint-foreground: var(--muted-foreground);
  --fc-classic-muted-foreground: var(--muted-foreground);

  /* neutral borders */
  --fc-classic-border: var(--border);
  --fc-classic-strong-border: color-mix(in oklch, var(--border) 70%, var(--foreground));

  color: var(--foreground);
}

/*
 * Today's number and header; the class is set from `isToday` above because v7 hashes its own.
 */
.event-calendar :deep(.fc-today-number) {
  color: var(--primary);
  font-weight: 700;
}

/*
 * A token pair, not an opacity fade (the two notes above). `:deep` because the class sits on a
 * FullCalendar descendant.
 */
.event-calendar :deep(.fc-event-past) {
  --fc-classic-event: var(--muted);
  --fc-classic-event-contrast: var(--muted-foreground);
}

/*
 * Today's events pulse like EventCard's live dot, thrown from the dot FullCalendar draws: the
 * event's first child and, being all border around a 0 px box, the only empty one, so
 * `:first-child:empty` is the one stable handle under v7's hashed class names. All-day bars have
 * no dot and match nothing. The ring's containing block is the empty centre, and `inset: -4px`
 * grows it to the dot's edge. Tailwind's `animate-ping`, spelled out because a keyframe cannot be
 * `@apply`ed into a scoped block.
 */
.event-calendar :deep(.fc-event-live > :first-child:empty) {
  position: relative;
}

.event-calendar :deep(.fc-event-live > :first-child:empty)::after {
  content: '';
  position: absolute;
  inset: -4px;
  border-radius: 9999px;
  background-color: var(--fc-event-color);
  opacity: 0.75;
}

@media (prefers-reduced-motion: no-preference) {
  .event-calendar :deep(.fc-event-live > :first-child:empty)::after {
    animation: live-ping 1s cubic-bezier(0, 0, 0.2, 1) infinite;
  }
}

@keyframes live-ping {
  75%,
  100% {
    transform: scale(2);
    opacity: 0;
  }
}
</style>
