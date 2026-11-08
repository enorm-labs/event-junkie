import { afterEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { defineComponent, h } from 'vue'
import type { PromoterDetail } from '@/api/types'
import { i18n } from '@/i18n'

const { getMock } = vi.hoisted(() => ({ getMock: vi.fn<(path: string) => Promise<unknown>>() }))

vi.mock('@/api/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/api/client')>()),
  api: { GET: getMock },
  unwrap: (promise: Promise<unknown>) => promise,
}))

const { default: PromoterDetailView } = await import('@/views/PromoterDetailView.vue')

const Page = defineComponent({ render: () => h('p') })

const concerts: PromoterDetail = {
  id: 3,
  slug: '36-concerts',
  name: '36 Concerts',
  websiteUrl: 'https://www.36-tickets.de/',
  instagramUrl: null,
  imageUrl: null,
  imageAttribution: null,
  imageLicenceId: null,
  imageSourceUrl: null,
  imageSources: [],
  intrinsicWidth: null,
  intrinsicHeight: null,
  description: null,
  descriptionLanguage: null,
  descriptionAlt: null,
  descriptionAltLanguage: null,
}

let wrapper: VueWrapper | undefined

afterEach(() => {
  wrapper?.unmount()
  wrapper = undefined
  getMock.mockReset()
  i18n.global.locale.value = 'en'
})

async function mountPromoter(promoter: PromoterDetail, locale: 'en' | 'de' = 'en') {
  i18n.global.locale.value = locale
  getMock.mockImplementation((path: string) =>
    Promise.resolve(path === '/api/promoters/{slug}' ? promoter : { content: [] }),
  )
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/:locale(en|de)/promoters/:slug', component: PromoterDetailView },
      { path: '/:locale(en|de)/:rest(.*)', component: Page },
    ],
  })
  await router.push(`/${locale}/promoters/${promoter.slug}`)
  wrapper = mount(PromoterDetailView, { global: { plugins: [router] } })
  await flushPromises()
  return wrapper
}

describe.each(['en', 'de'] as const)('PromoterDetailView in %s', (locale) => {
  it('links Instagram after the website', async () => {
    const view = await mountPromoter(
      { ...concerts, instagramUrl: 'https://www.instagram.com/36concerts/' },
      locale,
    )

    const anchors = [
      ...view
        .get('a[href="https://www.36-tickets.de/"]')
        .element.parentElement!.querySelectorAll('a'),
    ]
    expect(anchors.map((a) => a.textContent)).toEqual(['Website', 'Instagram'])
    expect(anchors.map((a) => a.getAttribute('href'))).toEqual([
      'https://www.36-tickets.de/',
      'https://www.instagram.com/36concerts/',
    ])
    expect(anchors.every((a) => a.getAttribute('rel') === 'noopener noreferrer')).toBe(true)
  })

  it('links Instagram alone for a promoter with no website', async () => {
    const view = await mountPromoter(
      { ...concerts, websiteUrl: null, instagramUrl: 'https://www.instagram.com/36concerts/' },
      locale,
    )

    const instagram = view.get('a[href="https://www.instagram.com/36concerts/"]')
    expect(instagram.text()).toBe('Instagram')
    expect(instagram.element.parentElement?.querySelectorAll('a')).toHaveLength(1)
  })

  it('draws no link line for a promoter with neither', async () => {
    const view = await mountPromoter({ ...concerts, websiteUrl: null }, locale)

    expect(view.findAll('a').some((a) => a.text() === 'Website' || a.text() === 'Instagram')).toBe(
      false,
    )
  })
})
