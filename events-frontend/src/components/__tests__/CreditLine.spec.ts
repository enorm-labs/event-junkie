import { describe, expect, it } from 'vitest'

import { mount } from '@vue/test-utils'
import CreditLine from '@/components/CreditLine.vue'

/**
 * What a CC BY or CC BY-SA picture (#1275) or description (#1837) obliges the page to show.
 *
 * The author, the licence and a link back to the original. A caption that names the author and
 * stops there is the failure worth an assertion, because it looks finished.
 */
describe('CreditLine', () => {
  const credit = {
    attribution: 'Photographer Name, via Wikimedia Commons',
    sourceUrl: 'https://commons.wikimedia.org/wiki/File:Example.jpg',
    licenceLabel: 'CC BY-SA 4.0',
    licenceUrl: 'https://creativecommons.org/licenses/by-sa/4.0/',
  }
  const hrefs = (wrapper: ReturnType<typeof mount>) =>
    wrapper.findAll('a').map((a) => a.attributes('href'))

  it('names the author and links both the original and the licence deed', () => {
    const wrapper = mount(CreditLine, { props: { credit, kind: 'photo' } })

    expect(wrapper.text()).toMatch(/^Photo:/)
    expect(wrapper.text()).toContain('Photographer Name, via Wikimedia Commons')
    expect(hrefs(wrapper)).toEqual([credit.sourceUrl, credit.licenceUrl])
  })

  it('sits under the content in muted text by default', () => {
    const classes = mount(CreditLine, { props: { credit, kind: 'photo' } })
      .get('p')
      .classes()

    expect(classes).toContain('text-muted-foreground')
    expect(classes).not.toContain('absolute')
  })

  it('lays a scrim under the credit when it sits on the picture', () => {
    const wrapper = mount(CreditLine, { props: { credit, kind: 'photo', overlay: true } })
    const classes = wrapper.get('p').classes()

    // Without a scrim the credit is unreadable on a bright photograph, which is not attribution.
    expect(classes).toContain('absolute')
    expect(classes).toContain('from-black/80')
    expect(classes).toContain('text-white')
    expect(wrapper.text()).toContain('Photographer Name, via Wikimedia Commons')
    expect(hrefs(wrapper)).toEqual([credit.sourceUrl, credit.licenceUrl])
  })

  it('credits a description as text, not as a photo', () => {
    const lead = {
      attribution: 'Wikipedia',
      sourceUrl: 'https://de.wikipedia.org/wiki/Giant_Rooks',
      licenceLabel: 'CC BY-SA 4.0',
      licenceUrl: 'https://creativecommons.org/licenses/by-sa/4.0/',
    }
    const wrapper = mount(CreditLine, { props: { credit: lead, kind: 'text' } })

    expect(wrapper.text()).toMatch(/^Text:/)
    expect(wrapper.text()).not.toContain('Photo:')
    expect(hrefs(wrapper)).toEqual([lead.sourceUrl, lead.licenceUrl])
  })

  it('still names a licence that has no deed to link', () => {
    const wrapper = mount(CreditLine, {
      props: {
        credit: { ...credit, licenceLabel: 'Public domain', licenceUrl: null },
        kind: 'photo',
      },
    })

    expect(wrapper.text()).toContain('Public domain')
    expect(wrapper.findAll('a')).toHaveLength(1)
  })
})
