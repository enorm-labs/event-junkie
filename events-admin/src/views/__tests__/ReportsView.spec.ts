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
let resolved: unknown[] = []
const fetchMock = vi.fn<typeof fetch>()

/** Presses Resolve on the row, then, in the sheet, picks the outcome, types the reply and submits. */
async function resolveIn(name: string, outcome?: 'unchanged', reply?: string) {
  const view = mounted!
  await view.find(`button[aria-label="Resolve: ${name}"]`).trigger('click')
  await flushPromises()
  if (outcome) document.querySelector<HTMLInputElement>(`input[value="${outcome}"]`)!.click()
  if (reply !== undefined) {
    const box = document.querySelector<HTMLTextAreaElement>('[data-testid="resolve-reply"]')!
    box.value = reply
    box.dispatchEvent(new Event('input'))
  }
  await flushPromises()
  document.querySelector<HTMLFormElement>('#resolve-form')!.requestSubmit()
  await flushPromises()
}

let mounted: ReturnType<typeof mount> | undefined
const mountView = () => (mounted = mount(ReportsView, { attachTo: document.body }))

beforeEach(() => {
  events = [tonight]
  accounts = []
  resolveStatus = 200
  resolved = []
  fetchMock.mockImplementation(async (input, init) => {
    const url = String(input)
    if ((init?.method ?? 'GET') === 'GET' && url === '/api/admin/reports') return json(events)
    if (url === '/api/admin/accounts') return json(accounts)
    if (init?.method === 'POST' && url === '/api/admin/reports/resolve') {
      if (resolveStatus !== 200) return json({ detail: 'database gone' }, resolveStatus)
      resolved.push(JSON.parse(String(init.body)))
      events = []
      return json({ resolved: 2, notified: 2 })
    }
    return json({ detail: `unexpected ${url}` }, 500)
  })
  vi.stubGlobal('fetch', fetchMock)
})
afterEach(() => {
  mounted?.unmount()
  mounted = undefined
  document.body.innerHTML = ''
  vi.unstubAllGlobals()
})

describe('ReportsView', () => {
  it('lists one row per event, the problems once each, and the notes', async () => {
    const view = mountView()
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
    const view = mountView()
    await flushPromises()

    const row = view.findAll('tbody tr')[0]!
    expect(row.findAll('[data-slot="badge"]').map((b) => b.text())).toEqual([
      'Venue',
      'Address or pin',
    ])
    expect(row.find('a').attributes('href')).toBe('https://event-junkie.de/de/venues/club')
    expect(row.text()).toContain('—')

    await resolveIn('Club')
    expect(resolved).toEqual([{ kind: 'venues', id: 7, outcome: 'fixed' }])
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
    const view = mountView()
    await flushPromises()

    expect(view.find('[data-testid="reporters"]').text()).toBe(
      'Open reports per account: a-masked@example.de (41), b-masked@example.de (3), c-masked@example.de (2), and 1 more',
    )
  })

  it('shows no line when nobody holds a report', async () => {
    const view = mountView()
    await flushPromises()
    expect(view.find('[data-testid="reporters"]').exists()).toBe(false)
  })

  it('resolves an event with the outcome and the reply from the sheet, and reloads', async () => {
    const view = mountView()
    await flushPromises()

    await resolveIn('Die Nerven', 'unchanged', '  The support act was not announced.  ')
    expect(resolved).toEqual([
      {
        kind: 'events',
        id: 418,
        outcome: 'unchanged',
        reply: 'The support act was not announced.',
      },
    ])
    expect(view.text()).toContain('No open reports.')
    expect(document.querySelector('#resolve-form')).toBeNull()
  })

  it('shows why a resolve failed, and keeps the row', async () => {
    resolveStatus = 500
    const view = mountView()
    await flushPromises()

    await resolveIn('Die Nerven')
    expect(document.querySelector('[role="alert"]')!.textContent).toContain('database gone')
    expect(view.text()).toContain('Die Nerven')
  })
})
