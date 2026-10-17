import { flushPromises, mount, RouterLinkStub } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import type { Festival } from '@/api/festivals'
import FestivalsView from '@/views/FestivalsView.vue'

function festival(id: number, name: string, firstDay: string, lastDay = firstDay): Festival {
  return {
    id,
    name,
    slug: name.toLowerCase().replace(/\W+/g, '-'),
    url: `https://${id}.example/programme`,
    firstDay,
    lastDay,
    noticeDe: `${name} auf Deutsch.`,
    noticeEn: `${name} in English.`,
    createdAt: null,
    updatedAt: null,
    eventCount: id * 3,
  }
}

let stored: Festival[] = []
const fetchMock = vi.fn<typeof fetch>()

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

beforeEach(() => {
  stored = [
    festival(2, 'Tag der Clubkultur 2026', '2026-10-03', '2026-10-11'),
    festival(1, 'Fête de la Musique 2026', '2026-06-21'),
  ]
  fetchMock.mockImplementation(async (input, init) => {
    const url = new URL(String(input), 'http://localhost')
    const method = init?.method ?? 'GET'
    const id = Number(/^\/api\/admin\/festivals\/(\d+)$/.exec(url.pathname)?.[1])
    if (method === 'GET' && url.pathname === '/api/admin/festivals') {
      return json({ content: stored, totalElements: stored.length })
    }
    if (method === 'POST' && url.pathname === '/api/admin/festivals') {
      const body = JSON.parse(String(init?.body))
      if (body.name.startsWith('Fete de la Musique 2026')) {
        return json(
          { detail: "A festival with slug 'fete-de-la-musique-2026' already exists" },
          409,
        )
      }
      return json({ ...festival(9, body.name, body.firstDay), ...body }, 201)
    }
    if (method === 'PUT' && id) {
      return json({ ...stored.find((f) => f.id === id)!, ...JSON.parse(String(init?.body)) })
    }
    if (method === 'DELETE' && id) return new Response(null, { status: 204 })
    return json({ detail: `unexpected ${method} ${url.pathname}` }, 404)
  })
  vi.stubGlobal('fetch', fetchMock)
})

afterEach(() => {
  document.body.innerHTML = ''
  vi.unstubAllGlobals()
  fetchMock.mockReset()
})

async function mountView() {
  const wrapper = mount(FestivalsView, {
    attachTo: document.body,
    global: { stubs: { RouterLink: RouterLinkStub } },
  })
  await flushPromises()
  return wrapper
}

const names = () =>
  [...document.body.querySelectorAll('[data-testid="festival-name"]')].map((span) =>
    span.textContent!.trim(),
  )

function button(label: string) {
  const found = [...document.body.querySelectorAll('button')].find(
    (b) => b.getAttribute('aria-label') === label || b.textContent?.trim() === label,
  )
  if (!found) throw new Error(`No button "${label}"`)
  return found
}

async function click(label: string) {
  button(label).click()
  await flushPromises()
}

async function fill(id: string, value: string) {
  const field = document.body.querySelector<HTMLInputElement | HTMLTextAreaElement>(`#${id}`)!
  field.value = value
  field.dispatchEvent(new Event('input'))
  await flushPromises()
}

async function fillForm(fields: Record<string, string>) {
  for (const [id, value] of Object.entries(fields)) await fill(`festival-${id}`, value)
}

async function submit() {
  document.body.querySelector('[data-testid="festival-form"]')!.dispatchEvent(new Event('submit'))
  await flushPromises()
}

const writes = () => fetchMock.mock.calls.filter(([, init]) => (init?.method ?? 'GET') !== 'GET')

const FETE_2027 = {
  name: 'Fête de la Musique 2027',
  url: 'https://www.fetedelamusique.de/',
  'first-day': '2027-06-21',
  'last-day': '2027-06-21',
  'notice-de': 'Musik an hunderten Orten der Stadt.',
  'notice-en': 'Music at hundreds of places across the city.',
}

describe('FestivalsView', () => {
  it('lists the festivals newest first with their days', async () => {
    await mountView()

    expect(String(fetchMock.mock.calls[0]![0])).toBe(
      '/api/admin/festivals?page=0&size=100&sort=firstDay,desc',
    )
    expect(names()).toEqual(['Tag der Clubkultur 2026', 'Fête de la Musique 2026'])
    expect(document.body.textContent).toContain('2026-10-03 – 2026-10-11')
    expect(document.body.querySelector('[data-testid="festival-count"]')!.textContent).toContain(
      '2 festivals',
    )
  })

  it('refuses a partial listing', async () => {
    fetchMock.mockImplementation(async () => json({ content: stored, totalElements: 120 }))

    const wrapper = await mountView()

    expect(wrapper.get('[role="alert"]').text()).toContain('Read 2 of 120 festivals')
  })

  it('creates a festival and puts it first', async () => {
    await mountView()
    await click('New festival')
    await fillForm(FETE_2027)
    await submit()

    const [path, init] = writes()[0]!
    expect(path).toBe('/api/admin/festivals')
    expect(init?.method).toBe('POST')
    expect(JSON.parse(String(init?.body))).toEqual({
      name: 'Fête de la Musique 2027',
      url: 'https://www.fetedelamusique.de/',
      firstDay: '2027-06-21',
      lastDay: '2027-06-21',
      noticeDe: 'Musik an hunderten Orten der Stadt.',
      noticeEn: 'Music at hundreds of places across the city.',
    })
    expect(names()[0]).toBe('Fête de la Musique 2027')
    expect(document.body.querySelector('[data-testid="festival-form"]')).toBeNull()
  })

  it('checks the days and the notices before it sends anything', async () => {
    await mountView()
    await click('New festival')
    await fillForm({ ...FETE_2027, 'last-day': '2027-06-20' })
    await submit()

    expect(writes()).toEqual([])
    expect(document.body.querySelector('[role="alert"]')!.textContent).toContain(
      'must not be before the first day',
    )

    await fillForm({ 'last-day': '2027-06-21', 'notice-en': ' ' })
    await submit()

    expect(writes()).toEqual([])
    expect(document.body.querySelector('[role="alert"]')!.textContent).toContain('both languages')
  })

  it('shows the importer’s 409 for a taken slug and keeps the form open', async () => {
    await mountView()
    await click('New festival')
    await fillForm({ ...FETE_2027, name: 'Fete de la Musique 2026' })
    await submit()

    expect(document.body.querySelector('[role="alert"]')!.textContent).toContain(
      "POST /api/admin/festivals: HTTP 409: A festival with slug 'fete-de-la-musique-2026'",
    )
    expect(document.body.querySelector('[data-testid="festival-form"]')).not.toBeNull()
  })

  it('edits a festival with its stored fields filled in', async () => {
    await mountView()
    await click('Edit Tag der Clubkultur 2026')

    expect(document.body.querySelector<HTMLInputElement>('#festival-name')!.value).toBe(
      'Tag der Clubkultur 2026',
    )
    await fill('festival-last-day', '2026-10-12')
    await submit()

    const [path, init] = writes()[0]!
    expect(path).toBe('/api/admin/festivals/2')
    expect(init?.method).toBe('PUT')
    expect(JSON.parse(String(init?.body))).toMatchObject({
      name: 'Tag der Clubkultur 2026',
      firstDay: '2026-10-03',
      lastDay: '2026-10-12',
      noticeDe: 'Tag der Clubkultur 2026 auf Deutsch.',
    })
    expect(document.body.textContent).toContain('2026-10-03 – 2026-10-12')
  })

  it('deletes a festival only after the confirmation', async () => {
    await mountView()
    await click('Delete Fête de la Musique 2026')

    expect(writes()).toEqual([])
    expect(document.body.querySelector('[data-testid="confirm-delete"]')!.textContent).toContain(
      'Fête de la Musique 2026',
    )

    await click('Cancel')
    expect(document.body.querySelector('[data-testid="confirm-delete"]')).toBeNull()
    expect(writes()).toEqual([])

    await click('Delete Fête de la Musique 2026')
    await click('Delete')

    const [path, init] = writes()[0]!
    expect(path).toBe('/api/admin/festivals/1')
    expect(init?.method).toBe('DELETE')
    expect(names()).toEqual(['Tag der Clubkultur 2026'])
  })
})

describe('FestivalsView event count (#3273)', () => {
  it("links each festival's count to its events on the Events page, past ones included", async () => {
    const wrapper = await mountView()

    const links = wrapper.findAllComponents(RouterLinkStub)
    expect(links.map((link) => link.text())).toEqual(['6', '3'])
    expect(links[0]!.props('to')).toEqual({
      path: '/events',
      query: { festival: '2', past: '1' },
    })
    expect(links[0]!.attributes('aria-label')).toBe('6 events of Tag der Clubkultur 2026')
  })
})
