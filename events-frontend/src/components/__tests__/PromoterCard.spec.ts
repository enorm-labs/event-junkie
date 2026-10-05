import { describe, expect, it } from 'vitest'

import { mount } from '@vue/test-utils'
import PromoterCard from '@/components/PromoterCard.vue'
import type { PromoterListItem } from '@/api/types'

const promoter: PromoterListItem = {
  slug: 'goodlive',
  name: 'Goodlive',
  description: 'Concerts and festivals.',
  descriptionLanguage: 'en',
  websiteUrl: 'https://www.goodlive.de/',
  upcomingEventCount: 220,
  upcomingNext30DaysCount: 12,
}

const stubs = {
  RouterLink: { template: '<a :href="to"><slot /></a>', props: ['to'] },
}

describe('PromoterCard', () => {
  // #2694: the venue card keeps the same order.
  it('reads name, description, both counts, then the website', () => {
    const wrapper = mount(PromoterCard, { props: { promoter }, global: { stubs } })

    expect(wrapper.findAll('h3, p, a[target="_blank"]').map((line) => line.text())).toEqual([
      'Goodlive',
      'Concerts and festivals.',
      '12 in the next 30 days · 220 upcoming',
      'goodlive.de',
    ])
  })

  it('says so when nothing is upcoming', () => {
    const wrapper = mount(PromoterCard, {
      props: { promoter: { ...promoter, upcomingEventCount: 0, upcomingNext30DaysCount: 0 } },
      global: { stubs },
    })

    expect(wrapper.text()).toContain('No upcoming events')
  })
})
