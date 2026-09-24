/**
 * The credit a venue, artist or promoter image has to carry (#1275): CC BY and CC BY-SA require
 * the author, the licence and a link to the original beside the picture, and the API returns the
 * three together. Resizing is a format change under CC 4.0 § 2(a)(4), not Adapted Material, so a
 * derivative carries the original's credit and share-alike never reaches the site.
 */

/** A row that may carry an attributed image. Every field is optional — the BFF marks none required. */
export interface AttributedImage {
  imageUrl?: string | null
  imageAttribution?: string | null
  imageLicenceId?: string | null
  imageSourceUrl?: string | null
}

export interface ImageCredit {
  attribution: string
  sourceUrl: string
  licenceLabel: string
  /** The licence deed, or `null` for a public-domain image, which has no deed to link. */
  licenceUrl: string | null
}

/**
 * Deed URL and display label per SPDX identifier, listed rather than derived: a pattern would
 * also produce a URL for an identifier Creative Commons does not publish, and the `-DE` ports are
 * separate licences with their own deeds. Every identifier `SPDX` in `scripts/venue-images.py`
 * can write appears here; an unknown one still credits its author and shows the identifier, with
 * no deed to click.
 */
const LICENCES: Record<string, { label: string; url: string | null }> = {
  'CC0-1.0': { label: 'CC0 1.0', url: 'https://creativecommons.org/publicdomain/zero/1.0/' },
  'CC-BY-2.0': { label: 'CC BY 2.0', url: 'https://creativecommons.org/licenses/by/2.0/' },
  'CC-BY-2.5': { label: 'CC BY 2.5', url: 'https://creativecommons.org/licenses/by/2.5/' },
  'CC-BY-3.0': { label: 'CC BY 3.0', url: 'https://creativecommons.org/licenses/by/3.0/' },
  'CC-BY-3.0-DE': { label: 'CC BY 3.0 DE', url: 'https://creativecommons.org/licenses/by/3.0/de/' },
  'CC-BY-4.0': { label: 'CC BY 4.0', url: 'https://creativecommons.org/licenses/by/4.0/' },
  'CC-BY-SA-2.0': { label: 'CC BY-SA 2.0', url: 'https://creativecommons.org/licenses/by-sa/2.0/' },
  'CC-BY-SA-2.0-DE': {
    label: 'CC BY-SA 2.0 DE',
    url: 'https://creativecommons.org/licenses/by-sa/2.0/de/',
  },
  'CC-BY-SA-2.5': { label: 'CC BY-SA 2.5', url: 'https://creativecommons.org/licenses/by-sa/2.5/' },
  'CC-BY-SA-3.0': { label: 'CC BY-SA 3.0', url: 'https://creativecommons.org/licenses/by-sa/3.0/' },
  'CC-BY-SA-3.0-DE': {
    label: 'CC BY-SA 3.0 DE',
    url: 'https://creativecommons.org/licenses/by-sa/3.0/de/',
  },
  'CC-BY-SA-4.0': { label: 'CC BY-SA 4.0', url: 'https://creativecommons.org/licenses/by-sa/4.0/' },
  PD: { label: 'Public domain', url: null },
  'LAL-1.3': { label: 'Free Art License 1.3', url: 'https://artlibre.org/licence/lal/en/' },
  // The licence statement is the Commons template itself, a grant an uploader wrote with no deed
  // to link (#1281).
  'LicenseRef-Commons-Attribution': {
    label: 'Attribution only',
    url: 'https://commons.wikimedia.org/wiki/Template:Attribution',
  },
}

/**
 * The credit for [row], or `null` where there is no image or no complete credit, so the missing
 * half is visible as an absent caption rather than a dangling label.
 */
export function imageCredit(row: AttributedImage | null | undefined): ImageCredit | null {
  if (!row?.imageUrl || !row.imageAttribution || !row.imageLicenceId || !row.imageSourceUrl) {
    return null
  }
  const licence = LICENCES[row.imageLicenceId]
  return {
    attribution: row.imageAttribution,
    sourceUrl: row.imageSourceUrl,
    licenceLabel: licence?.label ?? row.imageLicenceId,
    licenceUrl: licence?.url ?? null,
  }
}

/** A row whose description may be licensed text: an artist's Wikipedia lead (#1837). */
export interface AttributedText {
  description?: string | null
  descriptionAttribution?: string | null
  descriptionLicenceId?: string | null
  descriptionSourceUrl?: string | null
  descriptionAlt?: string | null
  descriptionAltAttribution?: string | null
  descriptionAltLicenceId?: string | null
  descriptionAltSourceUrl?: string | null
}

/**
 * The credit for [row]'s description on [side], or `null` for a text a venue or a person wrote,
 * which owes none. Each Wikipedia lead links its own article. CC BY-SA text carries the same three
 * parts as an image, so the licence table is shared.
 */
export function textCredit(
  row: AttributedText | null | undefined,
  side: 'original' | 'alt' = 'original',
): ImageCredit | null {
  const [text, attribution, licenceId, sourceUrl] =
    side === 'alt'
      ? [
          row?.descriptionAlt,
          row?.descriptionAltAttribution,
          row?.descriptionAltLicenceId,
          row?.descriptionAltSourceUrl,
        ]
      : [
          row?.description,
          row?.descriptionAttribution,
          row?.descriptionLicenceId,
          row?.descriptionSourceUrl,
        ]
  if (!text || !attribution || !licenceId || !sourceUrl) return null
  const licence = LICENCES[licenceId]
  return {
    attribution,
    sourceUrl,
    licenceLabel: licence?.label ?? licenceId,
    licenceUrl: licence?.url ?? null,
  }
}
