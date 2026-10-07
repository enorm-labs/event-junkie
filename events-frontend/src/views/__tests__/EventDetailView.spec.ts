import { afterEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { defineComponent, h } from 'vue'
import type { EventDetail } from '@/api/types'

const { getMock } = vi.hoisted(() => ({ getMock: vi.fn<(path: string) => Promise<unknown>>() }))

vi.mock('@/api/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/api/client')>()),
  api: { GET: getMock },
  unwrap: (promise: Promise<unknown>) => promise,
}))

const { default: EventDetailView } = await import('@/views/EventDetailView.vue')

const Page = defineComponent({ render: () => h('p') })

const TAG = 'utm_source=event-junkie.de&utm_medium=referral'

const night: EventDetail = {
  slug: 'a-night',
  title: 'A Night',
  eventDate: '2099-06-12',
  ticketUrl: 'https://tickets.example/a-night',
  sourceUrl: 'https://katerblau.de/programm?id=7',
  lineup: [
    {
      artist: {
        slug: 'mock-artist',
        name: 'Mock Artist',
        bandcampUrl: 'https://mock-artist.bandcamp.com/',
      },
      role: 'HEADLINER',
    },
  ],
}

let wrapper: VueWrapper | undefined

afterEach(() => {
  wrapper?.unmount()
  wrapper = undefined
  getMock.mockReset()
})

async function mountEvent(event: EventDetail) {
  getMock.mockImplementation((path: string) =>
    Promise.resolve(path === '/api/events/{slug}' ? event : []),
  )
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/:locale(en|de)/events/:slug', component: EventDetailView },
      { path: '/:locale(en|de)/:rest(.*)', component: Page },
    ],
  })
  await router.push(`/en/events/${event.slug}`)
  wrapper = mount(EventDetailView, { global: { plugins: [router] } })
  await flushPromises()
  return wrapper
}

function hrefOf(view: VueWrapper, text: string): string | undefined {
  return view
    .findAll('a')
    .find((link) => link.text() === text)
    ?.attributes('href')
}

describe('EventDetailView', () => {
  it('tags the ticket and source links as referrals from this site (#2770)', async () => {
    const view = await mountEvent(night)

    expect(hrefOf(view, 'Buy tickets')).toBe(`https://tickets.example/a-night?${TAG}`)
    expect(hrefOf(view, 'Event page')).toBe(`https://katerblau.de/programm?id=7&${TAG}`)
  })

  it("leaves an artist's Bandcamp link untagged", async () => {
    const view = await mountEvent(night)

    expect(hrefOf(view, 'Bandcamp')).toBe('https://mock-artist.bandcamp.com/')
  })
})
