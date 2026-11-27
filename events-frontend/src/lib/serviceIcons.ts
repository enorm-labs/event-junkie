import { type Component, type FunctionalComponent, h } from 'vue'
import { Globe, Link } from '@lucide/vue'
import {
  type SimpleIcon,
  siBandcamp,
  siDiscogs,
  siFacebook,
  siInstagram,
  siMusicbrainz,
  siSoundcloud,
  siSpotify,
  siYoutube,
} from 'simple-icons'

// Bundled, never fetched: a mark from the service's own server would send the visitor's IP there.
function brandIcon(icon: SimpleIcon): FunctionalComponent {
  const component: FunctionalComponent = (_, { attrs }) =>
    h('svg', { ...attrs, viewBox: '0 0 24 24', fill: 'currentColor' }, [
      h('path', { d: icon.path }),
    ])
  component.displayName = `${icon.slug}-icon`
  return component
}

/** simple-icons has no Resident Advisor mark, so it shares the generic link glyph. */
const icons = {
  website: Globe,
  bandcamp: brandIcon(siBandcamp),
  soundcloud: brandIcon(siSoundcloud),
  spotify: brandIcon(siSpotify),
  youtube: brandIcon(siYoutube),
  instagram: brandIcon(siInstagram),
  facebook: brandIcon(siFacebook),
  discogs: brandIcon(siDiscogs),
  residentAdvisor: Link,
  musicbrainz: brandIcon(siMusicbrainz),
}

export const serviceIcons: Record<keyof typeof icons, Component> = icons
