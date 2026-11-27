import { afterEach, describe, expect, it, vi } from 'vitest'

import { enableAutoUnmount, mount } from '@vue/test-utils'
import { setI18nLocale } from '@/i18n'
import { DEFAULT_LOCALE } from '@/i18n/locales'
import NoticesLicenceGroups from '@/components/NoticesLicenceGroups.vue'

vi.mock('@/assets/notices.json', () => ({
  default: {
    licenses: [
      {
        license: 'MIT',
        url: 'https://opensource.org/license/mit',
        components: [
          { name: 'vue', version: '3.5.41', url: 'https://vuejs.org', ecosystem: 'npm' },
          { name: 'pinia', version: '3.0.0', url: null, ecosystem: 'npm' },
        ],
      },
      {
        license: 'ISC',
        url: null,
        components: [{ name: 'semver', version: '7.7.0', url: null, ecosystem: 'npm' }],
      },
    ],
  },
}))

enableAutoUnmount(afterEach)

afterEach(() => setI18nLocale(DEFAULT_LOCALE))

const summaries = () =>
  mount(NoticesLicenceGroups)
    .findAll('summary')
    .map((summary) => summary.text().replace(/\s+/g, ' '))

describe('NoticesLicenceGroups', () => {
  it.each([
    ['en', ['MIT — 2 components', 'ISC — 1 component'], 'Read the MIT licence'],
    ['de', ['MIT — 2 Komponenten', 'ISC — 1 Komponente'], 'Lizenztext MIT lesen'],
  ] as const)('labels the groups in %s', async (locale, labels, linkText) => {
    setI18nLocale(locale)

    expect(summaries()).toEqual(labels)
    const links = mount(NoticesLicenceGroups).findAll('details > p a')
    expect(links.map((link) => link.text())).toEqual([linkText])
    expect(links[0]?.attributes('href')).toBe('https://opensource.org/license/mit')
  })

  it('renders the components of a group only once it is opened', async () => {
    const wrapper = mount(NoticesLicenceGroups)
    expect(wrapper.find('li').exists()).toBe(false)

    const details = wrapper.get('details').element as HTMLDetailsElement
    details.open = true
    await wrapper.get('details').trigger('toggle')

    expect(wrapper.findAll('li').map((item) => item.text())).toEqual(['vue@3.5.41', 'pinia@3.0.0'])
  })
})
