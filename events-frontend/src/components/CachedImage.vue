<script setup lang="ts">
import type { ImageSource } from '@/api/types'

/**
 * An image the API may hold better formats of.
 *
 * The BFF returns `imageUrl` plus an `imageSources` list — one entry per format, best first — once
 * the environment serves cached images (ADR-019). Given the list, this renders a `<picture>` and the
 * browser picks the first format it can decode at the width it needs. Given none, it renders the
 * plain `<img>` the site has always rendered, which is what a venue's own URL still gets.
 *
 * `sizes` is the caller's, because only the caller knows the layout. It is how the browser turns
 * `srcset`'s pixel widths into a choice, and a wrong one silently downloads the wrong file.
 */
defineProps<{
  /** The `src`, and the fallback for a browser that reads none of the offered formats. */
  src: string
  sources?: ImageSource[] | null
  alt: string
  /** How wide the image is drawn, as CSS. For a fixed slot that is a length: `80px`. */
  sizes: string
  /** Goes on the `<img>`, never on the wrapper — see the `contents` note in the template. */
  imgClass?: string
}>()
</script>

<template>
  <!--
    `contents` is load-bearing rather than tidy: it takes the <picture> out of the layout entirely,
    so the <img> stays the flex item its classes were written for. Without it every caller would
    have to split its classes across two elements.
  -->
  <picture class="contents">
    <source
      v-for="source in sources ?? []"
      :key="source.type"
      :type="source.type"
      :srcset="source.srcset"
      :sizes="sizes"
    />
    <!-- No `srcset` here: the last <source> is JPEG and matches every browser, so this is the
         fallback for one that does not support <picture> at all. -->
    <img :src="src" :alt="alt" :class="imgClass" loading="lazy" />
  </picture>
</template>
