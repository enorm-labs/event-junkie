import { computed, reactive, ref, shallowRef, type Ref } from 'vue'

import { describeError } from '@/api/client'
import type { EventPage, EventSummary } from '@/api/types'
import { yesterdayIso } from '@/lib/format'
import { withoutUpcoming } from '@/lib/pastEvents'
import { type EventSearchParams, searchEvents, useEventSearch } from './useEvents'

/** The one filter a detail page narrows its past events by. */
export type PastEventsFilter = Pick<EventSearchParams, 'venue' | 'artist' | 'promoter'>

export const PAST_PAGE_SIZE = 20

/** What `BaseDetailView` reads from {@link usePastEvents}: plain values, refs unwrapped. */
export interface PastEvents {
  events: EventSummary[]
  count: number
  hasMore: boolean
  loadingMore: boolean
  error: string | null
  loadMore: () => Promise<void>
}

/**
 * The past events of one venue, artist or promoter, newest first, loaded a page at a time (#2860).
 * Every page passes through `withoutUpcoming`, and `count` leaves out what it dropped: those are
 * runs that started before yesterday and still run, and they sort into the first pages.
 */
export function usePastEvents(
  filter: () => PastEventsFilter,
  upcoming: Readonly<Ref<EventPage | null>>,
) {
  // `to` with no `from` is every past event; the page size is what bounds a request.
  const params = (page: number): EventSearchParams => ({
    ...filter(),
    to: yesterdayIso(),
    size: PAST_PAGE_SIZE,
    sort: ['eventDate,desc'],
    ...(page > 0 ? { page } : {}),
  })

  // The first page goes through the response cache, so a page the visitor returns to keeps its height.
  const { data: first, run: runFirst } = useEventSearch(() => params(0))
  const later = shallowRef<EventPage[]>([])
  const loadingMore = ref(false)
  const error = ref<string | null>(null)
  let generation = 0

  const latest = computed(() => later.value[later.value.length - 1] ?? first.value)

  // A page can repeat an event when the archive grows between two clicks; the first copy wins.
  const loaded = computed(() => {
    const seen = new Set<string>()
    return [first.value, ...later.value]
      .flatMap((page) => page?.content ?? [])
      .filter((event) => !seen.has(event.slug ?? '') && seen.add(event.slug ?? ''))
  })
  const events = computed(() => withoutUpcoming(loaded.value, upcoming.value?.content))
  const count = computed(() =>
    Math.max(0, (latest.value?.totalElements ?? 0) - (loaded.value.length - events.value.length)),
  )
  const hasMore = computed(() => {
    const total = latest.value?.totalElements ?? 0
    return first.value !== null && (later.value.length + 1) * PAST_PAGE_SIZE < total
  })

  function run() {
    generation++
    later.value = []
    loadingMore.value = false
    error.value = null
    return runFirst()
  }

  async function loadMore() {
    if (loadingMore.value || !hasMore.value) return
    const call = generation
    loadingMore.value = true
    error.value = null
    try {
      const page = await searchEvents(params(later.value.length + 1))
      if (call !== generation) return
      later.value = [...later.value, page]
    } catch (e) {
      if (call !== generation) return
      error.value = describeError(e, 'errors.subject.pastEvents')
    } finally {
      if (call === generation) loadingMore.value = false
    }
  }

  const past: PastEvents = reactive({ events, count, hasMore, loadingMore, error, loadMore })
  return { past, run }
}
