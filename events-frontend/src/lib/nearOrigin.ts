import type { Origin } from '@/composables/useLocation'
import { formatDistance } from '@/lib/geo'

/** The "from …" line's name for the origin: a venue's own name, or the view's wording for the other two. */
export function describeOrigin(origin: Origin | null, t: (key: string) => string): string {
  if (!origin) return ''
  if (origin.source === 'device') return t('map.near.yourLocation')
  if (origin.source === 'map') return t('map.near.pickedPoint')
  return origin.label ?? ''
}

/** A fix coarser than the radius can put "near" venues anywhere in it; the view says so. */
export function coarseAccuracy(
  origin: Origin | null,
  radiusKm: number,
  locale: string,
): string | null {
  const accuracy = origin?.accuracyKm
  return accuracy && accuracy > radiusKm ? formatDistance(accuracy, locale) : null
}
