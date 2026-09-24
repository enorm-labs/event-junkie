import { afterEach, describe, expect, it } from 'vitest'

import { mount } from '@vue/test-utils'
import ForVenuesDe from '@/views/legal/ForVenuesView.de.vue'
import ForVenuesEn from '@/views/legal/ForVenuesView.en.vue'
import ImprintDe from '@/views/legal/ImprintView.de.vue'
import ImprintEn from '@/views/legal/ImprintView.en.vue'
import PrivacyDe from '@/views/legal/PrivacyView.de.vue'
import PrivacyEn from '@/views/legal/PrivacyView.en.vue'
import { CONTROLLER } from '@/lib/legal'
import { DEFAULT_LOCALE, type Locale } from '@/i18n/locales'
import { i18n } from '@/i18n'

/**
 * The mandatory-element checklists, run against each language version separately: the two are
 * separate documents (`views/localisedView.ts`), and the drift that matters is a section present
 * in one language and not the other. These pin the elements the law names; nothing automated can
 * tell you the two say the same thing.
 */

const stubs = {
  RouterLink: { template: '<a :href="to"><slot /></a>', props: ['to'] },
}

/**
 * Rendered text with whitespace collapsed, so an assertion does not depend on where the
 * formatter wrapped the paragraph.
 */
function textOf(component: unknown, locale: Locale): string {
  i18n.global.locale.value = locale
  return mount(component as never, { global: { stubs } })
    .text()
    .replace(/\s+/g, ' ')
}

afterEach(() => {
  i18n.global.locale.value = DEFAULT_LOCALE
})

const IMPRINT = { en: ImprintEn, de: ImprintDe } as const
const PRIVACY = { en: PrivacyEn, de: PrivacyDe } as const
const FOR_VENUES = { en: ForVenuesEn, de: ForVenuesDe } as const

/** Per-language wording for the same required element. */
interface Element {
  what: string
  en: RegExp
  de: RegExp
}

const IMPRINT_ELEMENTS: Element[] = [
  {
    what: 'the person responsible for editorial content (§ 18 MStV)',
    en: /§ 18 \(2\) MStV/,
    de: /§ 18 Abs\. 2 MStV/,
  },
  {
    what: 'the disclaimer in its formal register',
    en: /without warranty as to accuracy, completeness or timeliness/,
    de: /ohne Gewähr für Richtigkeit, Vollständigkeit und Aktualität/,
  },
  {
    what: 'a disclaimer for linked sites',
    en: /Liability for links/,
    de: /Haftung für Links/,
  },
  {
    what: 'our code licence, separated from third-party rights in the event data',
    en: /Apache License 2\.0.*remain the property of their respective rights holders/s,
    de: /Apache License 2\.0.*bleiben Eigentum der jeweiligen Rechteinhaber/s,
  },
  {
    what: 'the country in its own language',
    en: /Germany/,
    de: /Deutschland/,
  },
]

// Art. 13 has twelve mandatory elements (docs/LEGAL.md §7.2); omitting one is invisible without
// a checklist. This is that checklist, in both languages.
const PRIVACY_ELEMENTS: Element[] = [
  {
    what: 'absence of a DPO',
    en: /no data protection officer/i,
    de: /Datenschutzbeauftragter ist nicht bestellt/i,
  },
  {
    what: 'legal basis',
    en: /Art\. 6 \(1\) \(f\) GDPR/,
    de: /Art\. 6 Abs\. 1 lit\. f DSGVO/,
  },
  {
    what: 'legitimate interests, spelled out rather than merely asserted',
    en: /legitimate interest is operating/i,
    de: /berechtigtes Interesse ist der Betrieb/i,
  },
  // Two Art. 28 processors since #1233: Hetzner hosts, the translation engine receives event
  // descriptions. Art. 13 (1) (e) asks for the recipients, and a processor the notice does not name
  // is what #1233 found.
  { what: 'recipients', en: /Hetzner/, de: /Hetzner/ },
  { what: 'the translation processor', en: /Anthropic/, de: /Anthropic/ },
  // The transfer basis, since the second processor is in a third country (Art. 13 (1) (f)).
  {
    what: 'the transfer mechanism for the third country',
    en: /standard contractual clauses/i,
    de: /Standardvertragsklauseln/,
  },
  // Asserted as an absence: a CDN reappearing is a new recipient in a new third country.
  {
    what: 'no edge provider in front of the origin',
    en: /no content delivery network/i,
    de: /kein Content-Delivery-Netzwerk/i,
  },
  // The reproduction is a separate act from displaying a URL, and only §4 carries its legal basis
  // and retention; ADR-019 gave up the § 16 UrhG embedding position knowing that.
  {
    what: 'that images are downloaded and stored, not only linked',
    en: /downloads both kinds and stores a copy/i,
    de: /lädt\s+beides herunter und speichert eine Kopie/i,
  },
  // 43 of the 86 venue images come from Wikimedia Commons or Flickr (#1275), so a notice naming
  // only the venue as the source is wrong about half of them.
  {
    what: 'that venue photographs also come from open archives',
    en: /open archives such as Wikimedia Commons and Flickr/i,
    de: /offenen Archiven wie Wikimedia Commons\s+und Flickr/i,
  },
  // The inverse of #792's item: that one pinned the disclosure that images were fetched from venue
  // servers, deleted once `images.serving.enabled` was on in production (since 2026-08-30). This
  // pins the claim that costs something: turning serving off makes the page false, and fails here.
  {
    what: 'that no image request reaches a third party',
    en: /contacts\s+no venue, promoter or ticket seller/i,
    de: /keine Location, keinen Veranstalter und keinen Ticketanbieter an/i,
  },
  // GitHub, not a processor: opening an issue rather than writing an email sends data to a US
  // company, so the notice still addresses a third country.
  { what: 'third-country transfer — GitHub only', en: /US company/, de: /US-Unternehmen/ },
  // Mail to the role mailboxes is personal data with its own period (LEGAL.md §7.3a). Nothing
  // deletes it automatically, so the sentence is the only record of the promise.
  {
    what: 'mail retention, one year after the request is dealt with',
    en: /one year after that at the latest/,
    de: /spätestens ein Jahr danach/,
  },
  // #278. The real bound is 14 days, `ZO_COMPACT_DATA_RETENTION_DAYS` in both clusters'
  // `openobserve.yaml`, whose own comment says this notice must state whatever it says; the
  // kubelet rotation is a second, usually shorter, bound on volume. The number is the assertion:
  // a published claim under Art. 13 (2) (a), so changing the Helm value without the notice fails
  // here in both languages.
  {
    what: 'log retention, as the 14 days actually configured',
    en: /14 days/,
    de: /14 Tage/,
  },
  // One match anywhere satisfies an item, so the log period above stood in for the one §4 lacked.
  // This pins §4's own phrasing.
  {
    what: 'event-data retention, as a criterion, tied to the Art. 21 route',
    en: /no automatic deletion by age.*object to the processing\s+under Art\. 21 GDPR/s,
    de: /automatische Löschung nach Alter findet nicht statt.*Verarbeitung nach Art\. 21 DSGVO widersprichst/s,
  },
  // Backup retention is a separate period from log retention (#277). The numbers are
  // `backup_retention_days` in infra/modules/environment and `backup_retention_backstop_days` in
  // infra/bootstrap; these assertions couple them to the notice.
  {
    what: 'backup retention period, as a number',
    en: /normally kept for\s+30 days/i,
    de: /im\s+Regelfall\s+30 Tage/i,
  },
  // Both numbers, because only the second is a promise (#586): 30 is what the nightly sweep
  // achieves, 35 the bucket lifecycle rule that holds while the node is down. A period nothing
  // enforces is worse than a longer honest one (LEGAL.md §7.5).
  {
    what: 'backup retention ceiling, which is the enforced one',
    en: /within\s+35 days/i,
    de: /spätestens nach\s+35 Tagen/i,
  },
  // The interaction, not just the number: a deletion request and a restore have to be reconciled
  // somewhere (#277).
  {
    what: 'erasure reconciled with backups',
    en: /re-apply the erasure/i,
    de: /wenden wir die Löschung danach erneut an/i,
  },
  { what: 'right of access', en: /Art\. 15/, de: /Art\. 15 DSGVO/ },
  { what: 'right to rectification', en: /Art\. 16/, de: /Art\. 16 DSGVO/ },
  { what: 'right to erasure', en: /Art\. 17/, de: /Art\. 17 DSGVO/ },
  { what: 'right to restriction', en: /Art\. 18/, de: /Art\. 18 DSGVO/ },
  { what: 'right to portability', en: /Art\. 20/, de: /Art\. 20 DSGVO/ },
  {
    what: 'right to object, under its own heading',
    en: /Right to object \(Art\. 21 GDPR\)/,
    de: /Widerspruchsrecht \(Art\. 21 DSGVO\)/,
  },
  {
    what: 'right to complain to the competent supervisory authority',
    en: /Berliner Beauftragte/,
    de: /Berliner Beauftragte/,
  },
  {
    what: 'whether providing data is required',
    en: /neither legally nor contractually obliged/i,
    de: /weder gesetzlich noch vertraglich verpflichtet/i,
  },
  {
    what: 'absence of automated decision-making',
    en: /Art\. 22 GDPR/,
    de: /Art\. 22 DSGVO/,
  },
  {
    what: 'the local-storage keys and why they need no consent',
    en: /§ 25 \(2\) 2 TDDDG/,
    de: /§ 25 Abs\. 2 Nr\. 2 TDDDG/,
  },
  // §3 said "exactly one value" while the site wrote two, `theme` and `locale`; the checklist
  // above asserts presence, never truth. Naming both keys makes a third one fail here.
  {
    what: 'stored keys, both of them by name',
    en: /\btheme\b[\s\S]*\blocale\b/,
    de: /\btheme\b[\s\S]*\blocale\b/,
  },
  { what: 'that no cookies are set', en: /no cookies/i, de: /keine Cookies/i },
  {
    what: 'artist data as personal data',
    en: /artist is a natural person/i,
    de: /natürliche Person/i,
  },
  {
    what: 'a route to having an artist name removed',
    en: /removed or corrected/i,
    de: /entfernt oder korrigiert/i,
  },
]

// docs/SCRAPING_POSITION.md §5 is four steps and a deadline, published here; the steps are pinned
// per language like the statutory elements.
const FOR_VENUES_ELEMENTS: Element[] = [
  {
    what: 'that the source is switched off',
    en: /disable the source/i,
    de: /schalten die Quelle ab/i,
  },
  {
    what: 'that the events already imported are deleted too',
    en: /remove that venue's events/i,
    de: /löschen die Veranstaltungen/i,
  },
  {
    what: 'the seven-day answer',
    en: /within seven days/i,
    de: /innerhalb von sieben Tagen/i,
  },
  {
    // The narrower remedy #283 made possible: a per-field prohibition on the source, so a venue
    // objecting to its photographs alone need not lose its listing.
    what: 'that only the images or only the descriptions can be removed',
    en: /only the images you mind, or\s+only the description texts/i,
    de: /nur die Bilder stören oder nur die\s+Beschreibungstexte/i,
  },
  {
    // #807 decided that PROHIBITED stops storage, not only display: the material is deleted, not
    // hidden behind a gate.
    what: 'that the objected-to material is deleted rather than hidden',
    en: /delete\s+the material you objected to from our database/i,
    de: /löschen das beanstandete Material aus der Datenbank/i,
  },
  // `DELETE /api/admin/images/venues/{slug}` makes this true: a stored object outlives a takedown
  // unless something deletes it, so the page may only promise this while the endpoint exists.
  {
    what: "that the stored copies of a venue's images are deleted too",
    en: /delete the stored copies of your images/i,
    de: /löschen die gespeicherten Kopien eurer Bilder/i,
  },
  {
    what: 'that no reason is asked for',
    en: /do not ask for a reason/i,
    de: /fragen nicht nach einem Grund/i,
  },
  // The second route, cheaper for both sides. Dropping it leaves the page describing only the slow
  // half of §5.
  {
    what: 'a robots.txt rule as the route that needs no message',
    en: /needs no message/i,
    de: /braucht keine Nachricht/i,
  },
  {
    what: 'that robots.txt is checked on every request',
    en: /check every request against it/i,
    de: /prüfen jede Anfrage dagegen/i,
  },
]

for (const locale of ['en', 'de'] as const) {
  describe(`Imprint (${locale})`, () => {
    it('names the provider and a reachable postal address (§ 5 DDG)', () => {
      const text = textOf(IMPRINT[locale], locale)
      expect(text).toContain(CONTROLLER.name)
      expect(text).toContain(CONTROLLER.street)
      expect(text).toContain(CONTROLLER.city)
    })

    it('offers an email address, not only a web form', () => {
      i18n.global.locale.value = locale
      const wrapper = mount(IMPRINT[locale], { global: { stubs } })
      expect(wrapper.get(`a[href="mailto:${CONTROLLER.email}"]`)).toBeTruthy()
    })

    for (const element of IMPRINT_ELEMENTS) {
      it(`states ${element.what}`, () => {
        expect(textOf(IMPRINT[locale], locale)).toMatch(element[locale])
      })
    }
  })

  describe(`For venues (${locale})`, () => {
    it('offers the mailbox the route runs through', () => {
      i18n.global.locale.value = locale
      const wrapper = mount(FOR_VENUES[locale], { global: { stubs } })
      expect(wrapper.get(`a[href="mailto:${CONTROLLER.email}"]`)).toBeTruthy()
    })

    for (const element of FOR_VENUES_ELEMENTS) {
      it(`states ${element.what}`, () => {
        expect(textOf(FOR_VENUES[locale], locale)).toMatch(element[locale])
      })
    }
  })

  describe(`Privacy (${locale})`, () => {
    it('identifies the controller and how to reach them', () => {
      const text = textOf(PRIVACY[locale], locale)
      expect(text).toContain(CONTROLLER.name)
      expect(text).toContain(CONTROLLER.email)
    })

    for (const element of PRIVACY_ELEMENTS) {
      it(`states the ${element.what}`, () => {
        expect(textOf(PRIVACY[locale], locale)).toMatch(element[locale])
      })
    }

    it('does not describe processing that does not happen', () => {
      // A notice claiming cookie consent, analytics or ad partners we do not have is as inaccurate as
      // one omitting processing we do, and generators produce exactly that (§7.8).
      expect(textOf(PRIVACY[locale], locale)).not.toMatch(
        /Google Analytics|advertising partners|Werbepartner|withdraw your cookie consent|Cookie-Einwilligung/i,
      )
    })
  })
}

describe('across both language versions', () => {
  it('states which version prevails, on every page that has two', () => {
    // Two language versions with no stated precedence invite the reader to pick whichever suits
    // them. Six places to forget it, which is why LegalPage takes it as a prop.
    expect(textOf(IMPRINT.en, 'en')).toMatch(/the German version prevails/)
    expect(textOf(PRIVACY.en, 'en')).toMatch(/the German version prevails/)
    expect(textOf(FOR_VENUES.en, 'en')).toMatch(/the German version prevails/)
    expect(textOf(IMPRINT.de, 'de')).toMatch(/deutsche Fassung maßgeblich/)
    expect(textOf(PRIVACY.de, 'de')).toMatch(/deutsche Fassung maßgeblich/)
    expect(textOf(FOR_VENUES.de, 'de')).toMatch(/deutsche Fassung maßgeblich/)
  })

  it('carries one controller address across all four documents', () => {
    // §8.3: these must never disagree about the address. They share one module; this asserts the
    // sharing reaches the rendered output.
    for (const [component, locale] of [
      [IMPRINT.en, 'en'],
      [IMPRINT.de, 'de'],
      [PRIVACY.en, 'en'],
      [PRIVACY.de, 'de'],
    ] as const) {
      const text = textOf(component, locale)
      for (const line of [CONTROLLER.name, CONTROLLER.careOf, CONTROLLER.street, CONTROLLER.city]) {
        expect(text).toContain(line)
      }
    }
  })

  // All three flags are false, the go-live state ProvisionalNotice.vue describes, so this asserts
  // the banner's absence: a page still calling itself provisional after the facts became real is
  // wrong in the direction a reader acts on. A flag legitimately re-armed means inverting this
  // again, deliberately, alongside re-reading the notice.
  it('no longer calls the pages provisional, now that all three flags are settled', () => {
    for (const [component, locale] of [
      [IMPRINT.en, 'en'],
      [IMPRINT.de, 'de'],
      [PRIVACY.en, 'en'],
      [PRIVACY.de, 'de'],
    ] as const) {
      expect(textOf(component, locale)).not.toMatch(
        /This page is not final|Diese Seite ist noch nicht final/,
      )
    }
  })

  // A page calling a real rented address a placeholder is wrong about a fact a reader would act
  // on (§ 5 DDG).
  it('no longer calls the contact details placeholders, now that they are real', () => {
    for (const [component, locale] of [
      [IMPRINT.en, 'en'],
      [IMPRINT.de, 'de'],
      [PRIVACY.en, 'en'],
      [PRIVACY.de, 'de'],
    ] as const) {
      expect(textOf(component, locale)).not.toMatch(/placeholder|Platzhalter/i)
    }
  })
})
