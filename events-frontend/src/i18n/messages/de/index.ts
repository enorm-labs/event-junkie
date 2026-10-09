import calendar from './calendar.json'
import common from './common.json'
import dateRange from './dateRange.json'
import detail from './detail.json'
import errors from './errors.json'
import eventType from './eventType.json'
import events from './events.json'
import footer from './footer.json'
import home from './home.json'
import map from './map.json'
import legal from './legal.json'
import pageDescription from './pageDescription.json'
import pageTitle from './pageTitle.json'
import partyFeature from './partyFeature.json'
import promoters from './promoters.json'
import search from './search.json'
import venueCharacter from './venueCharacter.json'
import venueType from './venueType.json'
import venues from './venues.json'

/**
 * The German catalogue. Mirrors the English one file for file — the key-parity test in
 * `src/i18n/__tests__/messages.spec.ts` fails if the two ever diverge.
 *
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
  map,
  legal,
  pageDescription,
  pageTitle,
  partyFeature,
  promoters,
  search,
  venueCharacter,
  venueType,
  venues,
}
