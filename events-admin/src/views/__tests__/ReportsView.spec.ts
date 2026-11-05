import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import type { ReportedEvent } from '@/api/reports'
import ReportsView from '@/views/ReportsView.vue'
import { json } from '@/lib/__tests__/jsonResponse'

const tonight: ReportedEvent = {
  eventId: 418,
  slug: 'badehaus-die-nerven-2026-11-12',
  title: 'Die Nerven',
  eventDate: '2026-11-12',
  venue: 'Badehaus',
  sourceSlug: 'badehaus',
  reports: [
    {
      problems: ['ARTISTS', 'OTHER'],
      note: 'Support act missing',
      updatedAt: '2026-11-02T08:00:00Z',
    },
    { problems: ['ARTISTS'], note: null, updatedAt: '2026-11-02T09:00:00Z' },
  ],
}

let events: ReportedEvent[] = []
let resolveStatus = 200
const fetchMock = vi.fn<typeof fetch>()

beforeEach(() => {
  events = [tonight]
  resolveStatus = 200
  fetchMock.mockImplementation(async (input, init) => {
    const url = String(input)
    if ((init?.method ?? 'GET') === 'GET' && url === '/api/admin/reports') return json(events)
    if (init?.method === 'DELETE' && url === '/api/admin/reports?eventId=418') {
      if (resolveStatus !== 200) return json({ detail: 'database gone' }, resolveStatus)
      events = []
      return json({ resolved: 2 })
    }
    return json({ detail: `unexpected ${url}` }, 500)
  })
  vi.stubGlobal('fetch', fetchMock)
})
afterEach(() => vi.unstubAllGlobals())

describe('ReportsView', () => {
  it('lists one row per event, the problems once each, and the notes', async () => {
    const view = mount(ReportsView)
    await flushPromises()

    const row = view.findAll('tbody tr')
    expect(row).toHaveLength(1)
    expect(row[0]!.text()).toContain('Die Nerven')
    expect(row[0]!.text()).toContain('Badehaus')
    expect(row[0]!.findAll('[data-slot="badge"]').map((b) => b.text())).toEqual([
      'Artists',
      'Other',
    ])
    expect(row[0]!.text()).toContain('Support act missing')
    expect(row[0]!.find('a').attributes('href')).toBe(
      'https://event-junkie.de/de/events/badehaus-die-nerven-2026-11-12',
    )
  })

  it('resolves an event and reloads', async () => {
    const view = mount(ReportsView)
    await flushPromises()

    await view.find('button[aria-label="Resolve: Die Nerven"]').trigger('click')
    await flushPromises()
    expect(view.text()).toContain('No open reports.')
  })

  it('shows why a resolve failed, and keeps the row', async () => {
    resolveStatus = 500
    const view = mount(ReportsView)
    await flushPromises()

    await view.find('button[aria-label="Resolve: Die Nerven"]').trigger('click')
    await flushPromises()
    expect(view.find('[role="alert"]').text()).toContain('database gone')
    expect(view.text()).toContain('Die Nerven')
  })
})
