import { afterEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { defineComponent, h, ref } from 'vue'
import type { EventSummary } from '@/api/types'
import { setI18nLocale } from '@/i18n'

/** One upcoming event, so the Upcoming section renders its links; tonight stays empty. */
function loaded(data: EventSummary[]) {
  return {
    data: ref(data),
    loading: ref(false),
    error: ref<string | null>(null),
    run: vi.fn<() => void>(),
  }
}

vi.mock('@/composables/useEvents', () => ({
  useTodayEvents: () => loaded([]),
  useUpcomingEvents: () => loaded([{ slug: 'next', eventDate: '2026-10-10' }]),
}))

const { default: HomeView } = await import('@/views/HomeView.vue')

const stubs = {
  EventCard: { template: '<article />' },
  EventRow: { template: '<article />' },
  ClubStamp: { template: '<div />' },
  ClubkulturNotice: { template: '<div />' },
}

let mounted: VueWrapper | undefined

async function mountAt(locale: 'en' | 'de') {
  const Page = defineComponent({ render: () => h('p') })
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/:locale(en|de)/:rest(.*)*', component: Page }],
  })
  await router.push(`/${locale}`)
  setI18nLocale(locale)
  mounted = mount(HomeView, { global: { plugins: [router], stubs } })
  await flushPromises()
  return mounted
}

// Unmounted first: a live view re-renders when the locale switches back.
afterEach(() => {
  mounted?.unmount()
  mounted = undefined
  setI18nLocale('en')
})

describe('HomeView', () => {
  it.each([
    ['en', 'See all upcoming events', 'This week in Berlin'],
    ['de', 'Alle kommenden Events', 'Diese Woche in Berlin'],
  ] as const)('links this week beside all upcoming events in %s', async (locale, all, week) => {
    const wrapper = await mountAt(locale)
    const upcoming = wrapper.findAll('section').slice(-1)[0]!
    const links = upcoming.findAll('a').map((a) => [a.attributes('href'), a.text()])
    expect(links).toEqual([
      [`/${locale}/events`, all],
      [`/${locale}/week`, week],
    ])
  })
})
