<script setup lang="ts">
import { computed } from 'vue'
import { ImageOff } from '@lucide/vue'
import type { ImageSource } from '@/api/types'

/**
 * An image the API may hold better formats of. Given the BFF's `imageSources` list, best format
 * first (ADR-019), this renders a `<picture>`; given none, the plain `<img>` a venue's own URL
 * gets. `sizes` is the caller's, because only the caller knows the layout, and a wrong one
 * silently downloads the wrong file. With no `src` it draws a placeholder rather than nothing: a
 * card that closes up over a missing picture reads as broken, and that is 10% of the corpus (#811).
 */
const props = defineProps<{
  /** The `src`, and the fallback for a browser that reads none of the offered formats. */
  src?: string | null
  sources?: ImageSource[] | null
  alt: string
  /** How wide the image is drawn, as CSS. For a fixed slot that is a length: `80px`. */
  sizes: string
  /** Goes on the `<img>`, never on the wrapper (the `contents` note in the template). */
  imgClass?: string
  /**
   * The image's credit as one line of plain text, for a slot too small for `ImageCreditLine` (#1275).
   */
  title?: string | null
  /**
   * Whether this is the image the page is judged by, its LCP element: fetched at once and first,
   * where every other image is lazy. One per page (#1207).
   */
  priority?: boolean
  /**
   * A box to reserve, as a Tailwind aspect utility: `aspect-[3/2]`. Only a fixed slot needs this:
   * the intrinsic dimensions below reserve the original's shape, wrong where the caller crops to a
   * box, since a portrait flyer would still jump into a landscape slot.
   */
  aspect?: string
  /** The original's pixel size. Both or neither — the API reports them together. */
  intrinsicWidth?: number | null
  intrinsicHeight?: number | null
}>()

/**
 * `width` and `height`, or nothing: they give the browser the aspect ratio before the bytes
 * arrive, so a lazy image reserves its space. One of the pair is as bad as neither, so half an
 * answer returns an empty object. 16% of the stored images have no dimensions: a stock JVM reads
 * neither WebP nor AVIF, so nothing measured them at import (#848).
 */
const dimensions = computed(() =>
  props.intrinsicWidth != null && props.intrinsicHeight != null
    ? { width: props.intrinsicWidth, height: props.intrinsicHeight }
    : {},
)
</script>

<template>
  <!--
    Without an `aspect`, `contents` takes the <picture> out of the layout so the <img> stays the
    flex item its classes were written for. With one, the <picture> becomes the reserved box and
    the image fills it.
  -->
  <picture v-if="src" :class="aspect ? [aspect, 'block w-full overflow-hidden'] : 'contents'">
    <!--
      `contents` promotes the <source>s into the caller's flex container too: zero-width items that
      each claim a `gap`. Unhidden, three formats push the image 48 px right of a placeholder card's.
    -->
    <source
      v-for="source in sources ?? []"
      :key="source.type"
      class="hidden"
      :type="source.type"
      :srcset="source.srcset"
      :sizes="sizes"
    />
    <!-- No `srcset`: the last <source> is JPEG, so this is the fallback for a browser without
         <picture>. `dimensions` is dropped when the caller reserved a box; the two disagree by
         definition. -->
    <img
      v-bind="aspect ? {} : dimensions"
      :src="src"
      :alt="alt"
      :title="title ?? undefined"
      :class="[imgClass, aspect && 'size-full object-cover']"
      :loading="priority ? 'eager' : 'lazy'"
      :fetchpriority="priority ? 'high' : undefined"
    />
  </picture>
  <!--
    One placeholder for both kinds of nothing: a "withheld" variant would word a position no venue
    has taken (#811). `aspect-[3/2]` only acts where the caller leaves the height open (the detail
    header); the cards pass `size-20`/`size-24`.
  -->
  <div
    v-else
    :class="imgClass"
    class="flex aspect-[3/2] items-center justify-center bg-muted text-muted-foreground"
  >
    <ImageOff aria-hidden="true" class="size-1/4 max-h-12 min-h-4 min-w-4 max-w-12" />
  </div>
</template>
