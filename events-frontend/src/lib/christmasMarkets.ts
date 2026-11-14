// The Christmas markets' own page (#4972): an event feature set by hand, which the default lists leave out.

/** The event feature that marks a Christmas market, as `EventFeature.CHRISTMAS_MARKET` in the importer. */
export const CHRISTMAS_MARKET = 'christmas-market'

/**
 * Whether the home page points to the markets on [day], an ISO date: from 10 November to 6 January. The first
 * markets open in early November, and the last close after New Year.
 */
export function inChristmasMarketSeason(day: string): boolean {
  const monthDay = day.slice(5)
  return monthDay >= '11-10' || monthDay <= '01-06'
}
