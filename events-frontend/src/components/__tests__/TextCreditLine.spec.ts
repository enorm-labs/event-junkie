import { describe, expect, it } from 'vitest'

import { mount } from '@vue/test-utils'
import TextCreditLine from '@/components/TextCreditLine.vue'

/** What a CC BY-SA lead obliges the artist page to show (#1837): the source, the licence, both linked. */
describe('TextCreditLine', () => {
  it('links the article and the licence deed', () => {
    const wrapper = mount(TextCreditLine, {
      props: {
        credit: {
          attribution: 'Wikipedia',
          sourceUrl: 'https://de.wikipedia.org/wiki/Giant_Rooks',
          licenceLabel: 'CC BY-SA 4.0',
          licenceUrl: 'https://creativecommons.org/licenses/by-sa/4.0/',
        },
      },
    })

    expect(wrapper.text()).toContain('Wikipedia')
    expect(wrapper.text()).toContain('CC BY-SA 4.0')
    expect(wrapper.findAll('a').map((a) => a.attributes('href'))).toEqual([
      'https://de.wikipedia.org/wiki/Giant_Rooks',
      'https://creativecommons.org/licenses/by-sa/4.0/',
    ])
  })
})
