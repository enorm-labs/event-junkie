import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import type { ReportedSubject } from '@/api/reports'
import ReportsView from '@/views/ReportsView.vue'
import { json } from '@/lib/__tests__/jsonResponse'

const tonight: ReportedSubject = {
  kind: 'events',
  id: 418,
  slug: 'badehaus-die-nerven-2026-11-12',
  name: 'Die Nerven',
  eventDate: '2026-11-12',
  venue: 'Badehaus',
  sourceSlug: 'badehaus',
  reports: [
    {
      problems: ['GENRE', 'OTHER'],
      note: 'Support act missing',
      updatedAt: '2026-11-02T08:00:00Z',
    },
    { problems: ['ARTISTS'], note: null, updatedAt: '2026-11-02T09:00:00Z' },
  ],
}

const club: ReportedSubject = {
  kind: 'venues',
  id: 7,
  slug: 'club',
  name: 'Club',
  eventDate: null,
  venue: null,
  sourceSlug: null,
  reports: [{ problems: ['ADDRESS'], note: null, updatedAt: '2026-11-02T10:00:00Z' }],
}

let events: ReportedSubject[] = []
let accounts: { email: string; canReport: boolean; openReports: number }[] = []
let resolveStatus = 200
const fetchMock = vi.fn<typeof fetch>()

beforeEach(() => {
  events = [tonight]
  accounts = []
  resolveStatus = 200
  fetchMock.mockImplementation(async (input, init) => {
    const url = String(input)
    if ((init?.method ?? 'GET') === 'GET' && url === '/api/admin/reports') return json(events)
    if (url === '/api/admin/accounts') return json(accounts)
    if (init?.method === 'DELETE' && url === '/api/admin/reports?kind=events&id=418') {
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
      'Genre',
      'Other',
      'Artists',
    ])
    expect(row[0]!.text()).toContain('Support act missing')
    expect(row[0]!.find('a').attributes('href')).toBe(
      'https://event-junkie.de/de/events/badehaus-die-nerven-2026-11-12',
    )
  })

  it('shows a venue by its kind, links to its page, and resolves it by kind and id', async () => {
    events = [club]
    fetchMock.mockImplementation(async (input, init) => {
      const url = String(input)
      if (url === '/api/admin/accounts') return json([])
      if ((init?.method ?? 'GET') === 'GET') return json(events)
      if (url === '/api/admin/reports?kind=venues&id=7') {
        events = []
        return json({ resolved: 1 })
      }
      return json({ detail: `unexpected ${url}` }, 500)
    })
    const view = mount(ReportsView)
    await flushPromises()

    const row = view.findAll('tbody tr')[0]!
    expect(row.findAll('[data-slot="badge"]').map((b) => b.text())).toEqual([
      'Venue',
      'Address or pin',
    ])
    expect(row.find('a').attributes('href')).toBe('https://event-junkie.de/de/venues/club')
    expect(row.text()).toContain('—')

    await view.find('button[aria-label="Resolve: Club"]').trigger('click')
    await flushPromises()
    expect(view.text()).toContain('No open reports.')
  })

  it('names the accounts with the most open reports above the table, and sums the rest', async () => {
    accounts = [
      { email: 'b-masked@example.de', canReport: true, openReports: 3 },
      { email: 'none@example.de', canReport: true, openReports: 0 },
      { email: 'a-masked@example.de', canReport: true, openReports: 41 },
      { email: 'c-masked@example.de', canReport: true, openReports: 2 },
      { email: 'd-masked@example.de', canReport: false, openReports: 1 },
    ]
    const view = mount(ReportsView)
    await flushPromises()

    expect(view.find('[data-testid="reporters"]').text()).toBe(
      'Open reports per account: a-masked@example.de (41), b-masked@example.de (3), c-masked@example.de (2), and 1 more',
    )
  })

  it('shows no line when nobody holds a report', async () => {
    const view = mount(ReportsView)
    await flushPromises()
    expect(view.find('[data-testid="reporters"]').exists()).toBe(false)
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
