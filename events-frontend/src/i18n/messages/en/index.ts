import calendar from './calendar.json'
import common from './common.json'
import dateRange from './dateRange.json'
import detail from './detail.json'
import errors from './errors.json'
import eventType from './eventType.json'
import events from './events.json'
import footer from './footer.json'
import home from './home.json'
import legal from './legal.json'
import pageDescription from './pageDescription.json'
import pageTitle from './pageTitle.json'
import venues from './venues.json'

/**
 * The English catalogue, assembled from one file per feature.
 *
 * Split rather than flat so a change to the filter labels does not sit in the same diff as a change
 * to the footer — and so a translator can be handed one area at a time.
 *
 * **Long-form prose is deliberately absent.** The About page and the four legal pages are ~1,600
 * words carrying 29 inline links, `<strong>` and `<code>` elements *inside* their paragraphs.
 * Putting that in JSON would mean HTML inside strings rendered with `v-html`, or shattering
 * sentences into fragments that no translator could work from. Those pages become per-locale
 * components instead — see `views/localisedView.ts` for the reasoning.
 */
export default {
  calendar,
  common,
  dateRange,
  detail,
  errors,
  eventType,
  events,
  footer,
  home,
  legal,
  pageDescription,
  pageTitle,
  venues,
}
