/** A point on the map, in degrees. */
export interface Position {
  latitude: number
  longitude: number
}

/** The mean Earth radius (IUGG). Within Berlin the error against an ellipsoid is metres. */
const EARTH_RADIUS_KM = 6371.0088

function radians(degrees: number): number {
  return (degrees * Math.PI) / 180
}

/** The straight-line (great-circle) distance between two points, in kilometres. */
export function distanceKm(a: Position, b: Position): number {
  const dLatitude = radians(b.latitude - a.latitude)
  const dLongitude = radians(b.longitude - a.longitude)
  const h =
    Math.sin(dLatitude / 2) ** 2 +
    Math.cos(radians(a.latitude)) * Math.cos(radians(b.latitude)) * Math.sin(dLongitude / 2) ** 2
  return 2 * EARTH_RADIUS_KM * Math.asin(Math.min(1, Math.sqrt(h)))
}

/**
 * A distance as a person says it: metres in steps of 50 below a kilometre, then kilometres with
 * one decimal. The unit symbols are the same in both locales; the decimal separator is not.
 */
export function formatDistance(km: number, locale: string): string {
  if (km < 0.95) return `${Math.max(50, Math.round((km * 1000) / 50) * 50)} m`
  const value = new Intl.NumberFormat(locale, { maximumFractionDigits: 1 }).format(km)
  return `${value} km`
}

/**
 * A circle of `km` around `center` as a closed ring of `[longitude, latitude]`, for a GeoJSON
 * polygon. Flat-earth offsets are accurate to well under a metre at a city's scale.
 */
export function circlePolygon(center: Position, km: number, steps = 64): [number, number][] {
  const latitudeDegrees = (km / EARTH_RADIUS_KM) * (180 / Math.PI)
  const longitudeDegrees = latitudeDegrees / Math.cos(radians(center.latitude))
  const ring: [number, number][] = []
  for (let i = 0; i <= steps; i++) {
    const angle = (2 * Math.PI * i) / steps
    ring.push([
      center.longitude + longitudeDegrees * Math.cos(angle),
      center.latitude + latitudeDegrees * Math.sin(angle),
    ])
  }
  return ring
}
