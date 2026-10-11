import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import ListPage from '@/components/ListPage.vue'

describe('ListPage', () => {
  it('renders the title as the page h1 inside one main', () => {
    const wrapper = mount(ListPage, { props: { title: 'Venues' } })

    expect(wrapper.findAll('main')).toHaveLength(1)
    expect(wrapper.get('main > header > h1').text()).toBe('Venues')
  })

  it('renders the subtitle under the title when one is given', () => {
    const wrapper = mount(ListPage, { props: { title: 'Venues', subtitle: 'Every club' } })

    expect(wrapper.get('header > h1 + p').text()).toBe('Every club')
  })

  it.each([undefined, ''])('renders no subtitle paragraph for %j', (subtitle) => {
    const wrapper = mount(ListPage, { props: { title: 'Search', subtitle } })

    expect(wrapper.find('header p').exists()).toBe(false)
  })

  it('puts the header slot after the subtitle and the default slot after the header', () => {
    const wrapper = mount(ListPage, {
      props: { title: 'Venues', subtitle: 'Every club' },
      slots: { header: '<p class="extra">Missing one?</p>', default: '<section>Body</section>' },
    })

    const header = wrapper.get('header')
    expect(header.findAll('p').map((p) => p.text())).toEqual(['Every club', 'Missing one?'])
    expect(wrapper.get('main > header + section').text()).toBe('Body')
  })
})
