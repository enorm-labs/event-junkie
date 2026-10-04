import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import VenueFeaturesEmpty from '@/components/VenueFeaturesEmpty.vue'
import { i18n } from '@/i18n'

describe('VenueFeaturesEmpty', () => {
  it('names the cause: no venue has all the selected features', () => {
    const wrapper = mount(VenueFeaturesEmpty, {
      props: { features: ['wheelchair-accessible', 'queer'] },
    })

    expect(wrapper.text()).toContain(
      'No venue has all the selected features. Remove one to see more.',
    )
  })

  it('offers one remove control per feature, in display order', () => {
    const wrapper = mount(VenueFeaturesEmpty, {
      props: { features: ['wheelchair-accessible', 'queer', 'awareness-team'] },
    })

    const buttons = wrapper.findAll('button')
    expect(buttons.map((button) => button.text())).toEqual([
      'Queer',
      'Awareness team',
      'Wheelchair accessible',
    ])
    expect(buttons.map((button) => button.attributes('aria-label'))).toEqual([
      'Remove Queer',
      'Remove Awareness team',
      'Remove Wheelchair accessible',
    ])
  })

  it('emits the feature to remove', async () => {
    const wrapper = mount(VenueFeaturesEmpty, {
      props: { features: ['queer', 'wheelchair-accessible'] },
    })

    await wrapper.findAll('button')[1]!.trigger('click')

    expect(wrapper.emitted('remove')).toEqual([['wheelchair-accessible']])
  })

  it('says it in German too', async () => {
    i18n.global.locale.value = 'de'
    try {
      const wrapper = mount(VenueFeaturesEmpty, { props: { features: ['queer', 'smoke-free'] } })

      expect(wrapper.text()).toContain('Keine Location hat alle gewählten Merkmale.')
      expect(wrapper.findAll('button')[0]!.attributes('aria-label')).toMatch(/ entfernen$/)
    } finally {
      i18n.global.locale.value = 'en'
    }
  })
})
