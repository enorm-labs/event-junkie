import { describe, expect, it } from 'vitest'

import { mount } from '@vue/test-utils'
import AboutDe from '@/views/AboutView.de.md'
import AboutEn from '@/views/AboutView.en.md'
import ForVenuesDe from '@/views/legal/ForVenuesView.de.md'
import ForVenuesEn from '@/views/legal/ForVenuesView.en.md'
import ImprintDe from '@/views/legal/ImprintView.de.vue'
import ImprintEn from '@/views/legal/ImprintView.en.vue'
import PrivacyDe from '@/views/legal/PrivacyView.de.vue'
import PrivacyEn from '@/views/legal/PrivacyView.en.vue'

/**
 * A sentence that ends after a link must not render as `venue .`
 *
 * Vue condenses the newline between `</a>` and the full stop into a space, so the defect lives in
 * the gap between two lines of correct-looking markup and is invisible in review. The idiom that
 * avoids it splits the tag instead — `</a\n>.` — and the formatter can undo that at any time, in a
 * file nobody was editing.
 */
const PROSE_VIEWS = {
  AboutDe,
  AboutEn,
  ForVenuesDe,
  ForVenuesEn,
  ImprintDe,
  ImprintEn,
  PrivacyDe,
  PrivacyEn,
}

const stubs = { RouterLink: { template: '<a><slot /></a>' } }

describe.each(Object.entries(PROSE_VIEWS))('%s', (_name, component) => {
  it('never renders a space before punctuation', () => {
    const text = mount(component as never, { global: { stubs } })
      .text()
      .replace(/\s+/g, ' ')

    expect([...text.matchAll(/\S+ [.,:;]/g)].map((match) => match[0])).toEqual([])
  })
})
