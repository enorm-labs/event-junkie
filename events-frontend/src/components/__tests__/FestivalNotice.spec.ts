import { afterEach, describe, expect, it, vi } from 'vitest'

import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import FestivalNotice from '@/components/FestivalNotice.vue'
import type { Festival } from '@/api/types'
import { setI18nLocale } from '@/i18n'

const { getMock } = vi.hoisted(() => ({ getMock: vi.fn<(path: string) => Promise<unknown>>() }))

// The real `unwrap`, so an answer goes the way production takes it.
vi.mock('@/api/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/api/client')>()),
  api: { GET: getMock },
}))

const FETE: Festival = {
  slug: 'fete-de-la-musique-2027',
  name: 'Fête de la Musique 2027',
  url: 'https://www.fetedelamusique.de/',
  firstDay: '2027-06-21',
  lastDay: '2027-06-21',
  noticeDe: 'Musik an hunderten Orten der Stadt, draußen und kostenlos.',
  noticeEn: 'Music at hundreds of places across the city, outdoors and free.',
}

const CLUBKULTUR: Festival = {
  ...FETE,
  slug: 'tag-der-clubkultur-2027',
  name: 'Tag der Clubkultur 2027',
  firstDay: '2027-10-02',
  lastDay: '2027-10-10',
}

let mounted: VueWrapper | undefined

async function mountWith(answer: Festival[], locale: 'en' | 'de' = 'en') {
  setI18nLocale(locale)
  getMock.mockResolvedValue({
    data: answer,
    response: new Response(JSON.stringify(answer), { status: 200 }),
  })
  mounted = mount(FestivalNotice)
  await flushPromises()
  return mounted
}

afterEach(() => {
  mounted?.unmount()
  mounted = undefined
  getMock.mockReset()
  setI18nLocale('en')
})

describe('FestivalNotice', () => {
  it('asks the BFF for the current festival', async () => {
    await mountWith([FETE])

    expect(getMock).toHaveBeenCalledWith('/api/festivals/current')
  })

  it.each([
    ['en', '2 – 10 October', 'Music at hundreds of places', 'See the programme'],
    ['de', '2.–10. Oktober', 'Musik an hunderten Orten', 'Zum Programm'],
  ] as const)(
    'shows the name, the days and the notice in %s',
    async (locale, days, notice, link) => {
      const wrapper = await mountWith([CLUBKULTUR], locale)

      const aside = wrapper.get('[data-testid="festival-notice"]')
      expect(aside.attributes('aria-label')).toBe('Tag der Clubkultur 2027')
      expect(aside.text()).toContain('Tag der Clubkultur 2027')
      expect(aside.text().replace(/\s+/g, ' ')).toContain(days)
      expect(aside.text()).toContain(notice)
      expect(wrapper.get('a').text()).toBe(link)
    },
  )

  it('names a one-day festival by its one day', async () => {
    const wrapper = await mountWith([FETE])

    expect(wrapper.text()).toContain('· 21 June')
    expect(wrapper.text()).not.toContain('–')
  })

  it('links to the programme with rel="noopener" and loads nothing from the festival site', async () => {
    const wrapper = await mountWith([FETE])

    const link = wrapper.get('a')
    expect(link.attributes('href')).toBe('https://www.fetedelamusique.de/')
    expect(link.attributes('rel')).toBe('noopener')
    expect(wrapper.find('img').exists()).toBe(false)
    expect(wrapper.html()).not.toContain('src=')
  })

  it.each(['en', 'de'] as const)('stacks one notice per festival in %s', async (locale) => {
    const wrapper = await mountWith([CLUBKULTUR, FETE], locale)

    const asides = wrapper.findAll('[data-testid="festival-notice"]')
    expect(asides.map((aside) => aside.attributes('aria-label'))).toEqual([
      'Tag der Clubkultur 2027',
      'Fête de la Musique 2027',
    ])
    const notice = locale === 'de' ? FETE.noticeDe : FETE.noticeEn
    for (const aside of asides) expect(aside.text()).toContain(notice)
    expect(wrapper.findAll('a').map((link) => link.attributes('rel'))).toEqual([
      'noopener',
      'noopener',
    ])
  })

  it('renders nothing when the BFF answers an empty list', async () => {
    const wrapper = await mountWith([])

    expect(wrapper.find('aside').exists()).toBe(false)
  })

  it('renders nothing when the BFF answers something other than a list', async () => {
    const wrapper = await mountWith({ content: [] } as unknown as Festival[])

    expect(wrapper.find('aside').exists()).toBe(false)
  })

  it('renders nothing when the request fails', async () => {
    setI18nLocale('en')
    getMock.mockRejectedValue(new TypeError('offline'))
    mounted = mount(FestivalNotice)
    await flushPromises()

    expect(mounted.find('aside').exists()).toBe(false)
  })
})
