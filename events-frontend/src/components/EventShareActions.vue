<script setup lang="ts">
/**
 * The event page's action row: the page's own buttons from the slot, then one Share menu with
 * the native share sheet, the link and the calendar entries (#2564).
 */
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { CalendarPlus, ExternalLink, Link as LinkIcon, Send, Share2 } from '@lucide/vue'
import { Button } from '@/components/ui/button'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import type { EventDetail } from '@/api/types'
import {
  eventCalendarSpan,
  eventLocation,
  eventUid,
  googleCalendarUrl,
  toIcs,
  type CalendarEntry,
} from '@/lib/addToCalendar'
import { isPastEvent } from '@/lib/format'
import { canonicalUrl } from '@/lib/seo'
import { canShareNatively, copyLink, shareLink, type ShareOutcome } from '@/lib/share'
import type { Locale } from '@/i18n/locales'

const props = defineProps<{ event: EventDetail }>()

const { t, locale } = useI18n()

const pageUrl = computed(() =>
  canonicalUrl(locale.value as Locale, `/events/${props.event.slug ?? ''}`),
)

const nativeShare = canShareNatively()
const status = ref('')

function announce(outcome: ShareOutcome): void {
  status.value =
    outcome === 'copied'
      ? t('events.detail.share.copied')
      : outcome === 'failed'
        ? t('events.detail.share.copyFailed', { url: pageUrl.value })
        : ''
}

async function share(): Promise<void> {
  announce(await shareLink({ title: props.event.title, url: pageUrl.value }))
}

async function copy(): Promise<void> {
  announce(await copyLink(pageUrl.value))
}

// A cancelled or postponed night, or one that is over, is nothing to put in a calendar.
const calendarEntry = computed((): CalendarEntry | null => {
  const event = props.event
  if (!event.slug || !event.title || isPastEvent(event)) return null
  if (event.status === 'CANCELLED' || event.status === 'POSTPONED') return null
  const span = eventCalendarSpan(event)
  if (!span) return null
  return {
    uid: eventUid(event.slug),
    title: event.title,
    span,
    location: eventLocation(event),
    description: [event.subtitle, ...estimateNotes(span), pageUrl.value]
      .filter(Boolean)
      .join('\n\n'),
    url: pageUrl.value,
  }
})

function estimateNotes(span: CalendarEntry['span']): string[] {
  if (span.allDay) return []
  return [
    span.startEstimated ? t('events.detail.share.startEstimated') : '',
    span.endEstimated ? t('events.detail.share.endEstimated') : '',
  ].filter(Boolean)
}

const notes = computed(() => (calendarEntry.value ? estimateNotes(calendarEntry.value.span) : []))
const googleUrl = computed(() =>
  calendarEntry.value ? googleCalendarUrl(calendarEntry.value) : null,
)

/** The `.ics` as a Blob URL, revoked later rather than at once: Safari reads it after the click. */
function downloadIcs(): void {
  const entry = calendarEntry.value
  if (!entry) return
  const blob = new Blob([toIcs(entry)], { type: 'text/calendar;charset=utf-8' })
  const href = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = href
  anchor.download = `${props.event.slug}.ics`
  document.body.append(anchor)
  anchor.click()
  anchor.remove()
  setTimeout(() => URL.revokeObjectURL(href), 40_000)
}
</script>

<template>
  <section class="space-y-2">
    <div class="flex flex-wrap gap-3">
      <slot />
      <DropdownMenu>
        <DropdownMenuTrigger as-child>
          <Button variant="outline">
            <Share2 aria-hidden="true" />
            {{ t('events.detail.share.share') }}
          </Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="start">
          <DropdownMenuItem v-if="nativeShare" @select="share">
            <Send aria-hidden="true" />
            {{ t('events.detail.share.nativeShare') }}
          </DropdownMenuItem>
          <DropdownMenuItem @select="copy">
            <LinkIcon aria-hidden="true" />
            {{ t('events.detail.share.copyLink') }}
          </DropdownMenuItem>
          <template v-if="calendarEntry">
            <DropdownMenuItem @select="downloadIcs">
              <CalendarPlus aria-hidden="true" />
              {{ t('events.detail.share.ics') }}
            </DropdownMenuItem>
            <DropdownMenuItem v-if="googleUrl" as-child>
              <a :href="googleUrl" rel="noopener noreferrer" target="_blank">
                <ExternalLink aria-hidden="true" />
                {{ t('events.detail.share.google') }}
              </a>
            </DropdownMenuItem>
          </template>
        </DropdownMenuContent>
      </DropdownMenu>
    </div>
    <p v-if="notes.length" class="text-meta text-muted-foreground">{{ notes.join(' ') }}</p>
    <p class="text-body text-muted-foreground" role="status">{{ status }}</p>
  </section>
</template>
