import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import type { AdminVenue, CharacterTag } from '@/api/venues'
import VenuesView from '@/views/VenuesView.vue'

function venue(id: number, name: string, closedOn: string | null = null): AdminVenue {
  return { id, name, slug: name.toLowerCase().replace(/\W+/g, '-'), closedOn }
}

/** 148 open venues, then two the importer sorts among them by name, one of them closed. */
let all: AdminVenue[] = []
let tags: CharacterTag[] = []
const fetchMock = vi.fn<typeof fetch>()

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

beforeEach(() => {
  all = [
    venue(1, 'Æden'),
    venue(2, 'Anomalie', '2026-03-31'),
    ...Array.from({ length: 147 }, (_, i) => venue(100 + i, `Club ${String(i).padStart(3, '0')}`)),
    venue(3, 'SO36'),
  ]
  tags = [{ tag: 'queer', sourceUrl: 'https://so36.example/about' }]
  fetchMock.mockImplementation(async (input, init) => {
    const url = new URL(String(input), 'http://localhost')
    const method = init?.method ?? 'GET'
    if (method === 'GET' && url.pathname === '/api/admin/venues') {
      // The importer caps a page at 100.
      const page = Number(url.searchParams.get('page'))
      return json({ content: all.slice(page * 100, (page + 1) * 100), totalElements: all.length })
    }
    if (url.pathname === '/api/admin/venues/3/character-tags') return json(tags)
    const tag = /^\/api\/admin\/venues\/3\/character-tags\/([\w-]+)$/.exec(url.pathname)?.[1]
    if (method === 'PUT' && tag) return json({ tag, ...JSON.parse(String(init?.body)) })
    if (method === 'DELETE' && tag) return new Response(null, { status: 204 })
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
  const wrapper = mount(VenuesView, { attachTo: document.body })
  await flushPromises()
  return wrapper
}

const names = (wrapper: Awaited<ReturnType<typeof mountView>>) =>
  wrapper.findAll('[data-testid="venue-row"]').map((tr) => tr.find('td').text())

function button(label: string) {
  const found = document.body.querySelector<HTMLButtonElement>(`button[aria-label="${label}"]`)
  if (!found) throw new Error(`No button "${label}"`)
  return found
}

function field(slug: string) {
  return document.body.querySelector<HTMLInputElement>(`#tag-${slug}`)!
}

async function type(slug: string, value: string) {
  const input = field(slug)
  input.value = value
  input.dispatchEvent(new Event('input'))
  await flushPromises()
}

const writes = () => fetchMock.mock.calls.filter(([, init]) => (init?.method ?? 'GET') !== 'GET')

async function openTags(wrapper: Awaited<ReturnType<typeof mountView>>) {
  button('Character tags of SO36').click()
  await flushPromises()
  return wrapper
}

describe('VenuesView', () => {
  it('reads every page and puts a closed venue greyed at the end', async () => {
    const wrapper = await mountView()

    expect(fetchMock.mock.calls.map(([input]) => String(input))).toEqual([
      '/api/admin/venues?page=0&size=100&sort=name,asc',
      '/api/admin/venues?page=1&size=100&sort=name,asc',
    ])
    expect(wrapper.find('[data-testid="venue-count"]').text()).toBe('150 of 150 venues')
    const listed = names(wrapper)
    expect(listed).toHaveLength(150)
    expect(listed[0]).toBe('Æden')
    expect(listed[148]).toBe('SO36')
    expect(listed[149]).toMatch(/^Anomalie\s+Closed on 2026-03-31$/)
    expect(wrapper.findAll('[data-testid="venue-row"]')[149]!.classes()).toContain(
      'text-muted-foreground',
    )
  })

  it('filters by part of the name, ignoring case', async () => {
    const wrapper = await mountView()

    await wrapper.find('input[aria-label="Filter by name"]').setValue('  so3 ')

    expect(names(wrapper)).toEqual(['SO36'])
    expect(wrapper.find('[data-testid="venue-count"]').text()).toBe('1 of 150 venues')

    await wrapper.find('input[aria-label="Filter by name"]').setValue('nothing')
    expect(wrapper.text()).toContain('No venue matches.')
  })

  it('shows a partial listing as an error', async () => {
    all = all.slice(0, 120)
    fetchMock.mockImplementation(async () =>
      json({ content: all.slice(0, 100), totalElements: 120 }),
    )

    const wrapper = await mountView()

    expect(wrapper.find('[role="alert"]').text()).toContain('Read 100 of 120 venues.')
    expect(names(wrapper)).toEqual([])
  })

  it('opens the 14 tags in the enum order, each with its stored source URL', async () => {
    await openTags(await mountView())

    const rows = [...document.body.querySelectorAll('[data-tag]')]
    expect(rows.map((li) => li.getAttribute('data-tag'))).toHaveLength(14)
    expect(rows[0]!.getAttribute('data-tag')).toBe('queer')
    expect(rows[13]!.getAttribute('data-tag')).toBe('wheelchair-accessible')
    expect(field('queer').value).toBe('https://so36.example/about')
    expect(field('awareness-team').value).toBe('')
    expect(rows[3]!.textContent).toContain('awareness-team · not set')
  })

  it('sets a tag with a PUT of its source URL', async () => {
    await openTags(await mountView())

    await type('awareness-team', ' https://so36.example/awareness ')
    button('Save Awareness team').click()
    await flushPromises()

    expect(writes()).toHaveLength(1)
    const [url, init] = writes()[0]!
    expect(String(url)).toBe('/api/admin/venues/3/character-tags/awareness-team')
    expect(init?.method).toBe('PUT')
    expect(JSON.parse(String(init?.body))).toEqual({ sourceUrl: 'https://so36.example/awareness' })
    expect(button('Clear Awareness team')).toBeTruthy()
  })

  it('changes a source URL with another PUT', async () => {
    await openTags(await mountView())
    expect(button('Save Queer').disabled).toBe(true)

    await type('queer', 'https://so36.example/queer')
    button('Save Queer').click()
    await flushPromises()

    const [url, init] = writes()[0]!
    expect(String(url)).toBe('/api/admin/venues/3/character-tags/queer')
    expect(JSON.parse(String(init?.body))).toEqual({ sourceUrl: 'https://so36.example/queer' })
  })

  it('refuses a URL that is not https:// before any request', async () => {
    await openTags(await mountView())

    await type('quiet-room', 'http://so36.example/quiet')
    button('Save Quiet room').click()
    await flushPromises()

    expect(writes()).toEqual([])
    expect(
      document.body.querySelector('[data-tag="quiet-room"] [role="alert"]')?.textContent,
    ).toContain('https:// URL')
  })

  it('clears a tag only after the confirmation, with a DELETE', async () => {
    await openTags(await mountView())

    button('Clear Queer').click()
    await flushPromises()
    expect(writes()).toEqual([])
    const confirm = document.body.querySelector('[data-testid="confirm-clear"]')!
    expect(confirm.textContent).toContain('Remove Queer from SO36?')

    confirm.querySelector('button')!.click()
    await flushPromises()

    expect(writes()).toHaveLength(1)
    const [url, init] = writes()[0]!
    expect(String(url)).toBe('/api/admin/venues/3/character-tags/queer')
    expect(init?.method).toBe('DELETE')
    expect(field('queer').value).toBe('')
    expect(document.body.querySelector('button[aria-label="Clear Queer"]')).toBeNull()
  })

  it('sends nothing when the clearing is cancelled', async () => {
    await openTags(await mountView())

    button('Clear Queer').click()
    await flushPromises()
    document.body
      .querySelectorAll<HTMLButtonElement>('[data-testid="confirm-clear"] button')[1]!
      .click()
    await flushPromises()

    expect(writes()).toEqual([])
    expect(document.body.querySelector('[data-testid="confirm-clear"]')).toBeNull()
    expect(field('queer').value).toBe('https://so36.example/about')
  })
})
