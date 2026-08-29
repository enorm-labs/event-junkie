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
 * The mandatory-element checklists, run against **each language version separately**.
 *
 * That separation is the point. The two versions are separate documents (see
 * `views/localisedView.ts`), which buys reviewability at the cost of drift — and the drift that
 * matters is not a clumsy sentence, it is a section that exists in one language and not the other.
 * Checking only English would leave a German notice missing its Widerspruchsrecht entirely green.
 *
 * What these tests cannot do is tell you the two say the *same* thing. Nothing automated can. They
 * pin the elements the law names, and `@/lib/legal` holds the facts that would otherwise be typed
 * twice.
 */

const stubs = {
  RouterLink: { template: '<a :href="to"><slot /></a>', props: ['to'] },
}

/**
 * Rendered text with runs of whitespace collapsed.
 *
 * Templates wrap prose across source lines, so a sentence that reads as one phrase on the page is
 * broken by newlines and indentation in `textContent`. Without this, an assertion passes or fails
 * depending on where Prettier happened to wrap the paragraph.
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

// Art. 13 has twelve mandatory elements (docs/LEGAL.md §7.2); omitting one is the
// usual defect, and it is invisible without a checklist. This is that checklist, in both languages.
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
  // Hetzner is the only Art. 28 processor since ADR-012's 2026-08-10 amendment dropped Cloudflare.
  // The second half asserts the *absence* deliberately: a CDN reappearing in front of the site is a
  // new recipient and a new third country, and it must not be able to arrive without this failing.
  { what: 'recipients', en: /Hetzner/, de: /Hetzner/ },
  {
    what: 'no edge provider in front of the origin',
    en: /no content delivery network/i,
    de: /kein Content-Delivery-Netzwerk/i,
  },
  // The reproduction is a *separate* act from displaying a URL, and only §4 can carry its legal
  // basis and its retention. ADR-019 chose it knowing it gives up the embedding position under
  // § 16 UrhG, so a notice that describes the storage is the price of that decision.
  {
    what: 'that images are downloaded and stored, not only linked',
    en: /downloads those images and stores a copy/i,
    de: /lädt diese Bilder herunter und speichert eine Kopie/i,
  },
  // #792. The claim above it — no CDN, no proxy, your request reaches us directly — is what makes
  // this necessary: without it the notice reads as though nothing else is ever contacted, while
  // four render sites still point a browser at a venue's server (#833). Delete this item when that
  // stops being true, not before.
  {
    what: 'that some images are still fetched from a third-party server, and what that discloses',
    en: /loaded directly\s+from the servers of venues, promoters and ticket sellers/i,
    de: /direkt von\s+den Servern der Locations, Veranstalter und Ticketanbieter geladen/i,
  },
  // GitHub, not a processor: the notice still has to address a third country, because choosing to
  // open an issue rather than write an email sends data to a US company.
  { what: 'third-country transfer — GitHub only', en: /US company/, de: /US-Unternehmen/ },
  {
    what: 'log retention period',
    en: /deleted after seven days/i,
    de: /nach sieben Tagen gelöscht/i,
  },
  // One match anywhere satisfies an item, so the log period above stood in for the one §4 lacked.
  // This pins §4's own phrasing: §6's Art. 21 heading would survive §4's sentence being deleted.
  {
    what: 'event-data retention, as a criterion, tied to the Art. 21 route',
    en: /no automatic deletion by age.*object to the processing\s+under Art\. 21 GDPR/s,
    de: /automatische Löschung nach Alter findet nicht statt.*Verarbeitung nach Art\. 21 DSGVO widersprichst/s,
  },
  // Backup retention is a *separate* period from log retention and the notice has to carry both
  // (#277). The numbers are `backup_retention_days` in infra/modules/environment and
  // `backup_retention_backstop_days` in infra/bootstrap, whose own comments record that they exist
  // to be stated here — so these assertions are what couple them: change a variable and this fails
  // until the notice follows.
  {
    what: 'backup retention period, as a number',
    en: /normally kept for\s+30 days/i,
    de: /im\s+Regelfall\s+30 Tage/i,
  },
  // **Both numbers, because only the second one is a promise (#586).** 30 is what the nightly sweep
  // on the node achieves; 35 is the bucket lifecycle rule, and it is the only figure that still
  // holds while the node is down. A notice stating 30 alone was true only on a healthy schedule,
  // which is the defect LEGAL.md §7.5 names — a period nothing enforces is worse than a longer
  // honest one. Dropping this assertion would let the notice quietly go back to promising 30.
  {
    what: 'backup retention ceiling, which is the enforced one',
    en: /within\s+35 days/i,
    de: /spätestens nach\s+35 Tagen/i,
  },
  // The interaction, not just the number: a deletion request and a restore have to be reconciled
  // somewhere, and leaving it implicit is the defect #277 was filed for.
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
    what: 'the local-storage key and why it needs no consent',
    en: /§ 25 \(2\) 2 TDDDG/,
    de: /§ 25 Abs\. 2 Nr\. 2 TDDDG/,
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

// docs/SCRAPING_POSITION.md §5 is four steps and a deadline, and this page is where that
// commitment gets published — an operator who cannot find it has not been made one. So the steps
// are pinned the same way the statutory elements above are, per language.
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
    // The narrower remedy #283 made possible: a per-field prohibition on the source. Before it, the
    // only lever was disabling the whole source, so a venue objecting to its photographs alone had
    // to lose its listing. The page has to offer what the system can actually do.
    what: 'that only the images or only the descriptions can be removed',
    en: /only the images you mind, or\s+only the description texts/i,
    de: /nur die Bilder stören oder nur die\s+Beschreibungstexte/i,
  },
  {
    // #807 decided that PROHIBITED stops storage and not only display. This page promised removal
    // before that was true, so the assertion pins the half that costs something: the material is
    // deleted from the database, not hidden behind a gate.
    what: 'that the objected-to material is deleted rather than hidden',
    en: /delete\s+the material you objected to from our database/i,
    de: /löschen das beanstandete Material aus der Datenbank/i,
  },
  // PR 6 built the route that makes this true (`DELETE /api/admin/images/venues/{slug}`). Under
  // hotlinking a takedown propagated by itself; now a stored object outlives it unless something
  // deletes it, so the page may only promise this while that endpoint exists.
  {
    what: 'that the stored copies of a venue\'s images are deleted too',
    en: /delete the stored copies of your images/i,
    de: /löschen die gespeicherten Kopien eurer Bilder/i,
  },
  {
    what: 'that no reason is asked for',
    en: /do not ask for a reason/i,
    de: /fragen nicht nach einem Grund/i,
  },
  // The second route, and the cheaper one for both sides: it costs the operator no message and us
  // no inbox. Dropping it would leave the page describing only the slow half of §5.
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
      // A notice claiming cookie consent, analytics or ad partners we do not have is as inaccurate
      // as one omitting processing we do — and generators produce exactly that (§7.8).
      expect(textOf(PRIVACY[locale], locale)).not.toMatch(
        /Google Analytics|advertising partners|Werbepartner|withdraw your cookie consent|Cookie-Einwilligung/i,
      )
    })
  })
}

describe('across both language versions', () => {
  it('states which version prevails, on every page that has two', () => {
    // Two language versions with no stated precedence is worse than one language: it invites the
    // reader to pick whichever suits them. Three pages, both languages — six places to forget it,
    // which is why LegalPage takes it as a prop rather than each page typing the sentence.
    expect(textOf(IMPRINT.en, 'en')).toMatch(/the German version prevails/)
    expect(textOf(PRIVACY.en, 'en')).toMatch(/the German version prevails/)
    expect(textOf(FOR_VENUES.en, 'en')).toMatch(/the German version prevails/)
    expect(textOf(IMPRINT.de, 'de')).toMatch(/deutsche Fassung maßgeblich/)
    expect(textOf(PRIVACY.de, 'de')).toMatch(/deutsche Fassung maßgeblich/)
    expect(textOf(FOR_VENUES.de, 'de')).toMatch(/deutsche Fassung maßgeblich/)
  })

  it('carries one controller address across all four documents', () => {
    // §8.3: these must never disagree about the address, and now there are four of them. They
    // share one module; this asserts the sharing actually reaches the rendered output.
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

  // This asserted "not final ... placeholders" until 2026-08-21, when the Postflex address was
  // rented and CONTACT_DETAILS_ARE_PROVISIONAL went false. The banner is still there — nothing is
  // deployed — but it no longer claims the contact details are fake, so the old regex failed. That
  // is the tripwire working: it caught a page and a test disagreeing about reality.
  it('still says the pages are not final, because nothing is deployed', () => {
    expect(textOf(IMPRINT.en, 'en')).toMatch(/This page is not final.*not deployed yet/s)
    expect(textOf(PRIVACY.en, 'en')).toMatch(/This page is not final.*not deployed yet/s)
    expect(textOf(IMPRINT.de, 'de')).toMatch(/Diese Seite ist noch nicht final.*nicht in Betrieb/s)
    expect(textOf(PRIVACY.de, 'de')).toMatch(/Diese Seite ist noch nicht final.*nicht in Betrieb/s)
  })

  // The direction that now matters more. A page calling a real rented address a placeholder is
  // worse than one that called a fake address a placeholder: the first is wrong about a fact a
  // reader would act on, and § 5 DDG asks for an address a reader can rely on.
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
