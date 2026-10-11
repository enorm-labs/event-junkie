<script lang="ts" setup>
import type { RouteLocationRaw } from 'vue-router'
import { RouterLink } from 'vue-router'
import type { ImageSource } from '@/api/types'
import CachedImage from '@/components/CachedImage.vue'
import { CARD_CLASS, CARD_POSTER_CLASS } from '@/lib/utils'
import { useViewportFocus } from '@/composables/useViewportFocus'

/**
 * The link and poster box an `EventCard` and a `VenueCard` share. The card owns the text below,
 * the drawn poster for a missing image (`#fallback`), and anything laid over the poster
 * (`#overlay`, which makes the box `relative`).
 */
withDefaults(
  defineProps<{
    to: RouteLocationRaw
    imageUrl?: string | null
    imageSources?: ImageSource[] | null
    alt: string
    /** The first card of a list, whose poster is a likely LCP element: see `CachedImage`. */
    priority?: boolean
    /** The image's credit as one line of plain text: see `CachedImage`. */
    title?: string | null
    aspect?: string
    /** The real slot: the whole viewport below `sm`, about 474 px in the two-column grid above. */
    sizes?: string
  }>(),
  { aspect: 'aspect-poster', sizes: '(min-width: 640px) 474px, 100vw' },
)

// Reveals the poster while the card passes the middle of a touch screen, where `:hover` is dead.
const { el: posterEl, focused: posterFocused } = useViewportFocus()
</script>

<template>
  <RouterLink :to="to" :class="CARD_CLASS">
    <div
      ref="posterEl"
      :data-focus="posterFocused || undefined"
      :class="$slots.overlay ? [CARD_POSTER_CLASS, 'relative'] : CARD_POSTER_CLASS"
    >
      <CachedImage
        v-if="imageUrl"
        :src="imageUrl"
        :sources="imageSources"
        :alt="alt"
        :title="title"
        :priority="priority"
        :aspect="aspect"
        :sizes="sizes"
        img-class="grayscale transition duration-300 group-hover:grayscale-0 group-data-focus/poster:grayscale-0"
      />
      <slot v-else name="fallback" />
      <slot name="overlay" />
    </div>
    <slot />
  </RouterLink>
</template>
