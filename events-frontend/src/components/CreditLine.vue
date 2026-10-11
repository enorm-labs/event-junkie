<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import type { ImageCredit } from '@/lib/imageCredit'

/**
 * The credit for a licensed picture (#1275) or a licensed description (#1837).
 *
 * CC BY and CC BY-SA are usable only with the author named, the licence named and the original
 * linked, so this is part of the licence rather than a courtesy.
 *
 * **A picture's credit sits beside `CachedImage`, not inside it.** That component renders a
 * `display: contents` <picture> with no box of its own, so a caption within it would have nothing
 * to sit under.
 *
 * `overlay` lays it over the foot of the picture instead of under it. The scrim is not decoration:
 * these photographs run from a near-black club interior to a white-sky facade, so plain text over
 * them is unreadable on a third of the venues. It is never revealed on hover, because a touch
 * device has none.
 */
withDefaults(defineProps<{ credit: ImageCredit; kind: 'photo' | 'text'; overlay?: boolean }>(), {
  overlay: false,
})

const { t } = useI18n()
</script>

<template>
  <p
    :class="
      overlay
        ? 'absolute inset-x-0 bottom-0 bg-gradient-to-t from-black/80 via-black/55 to-transparent px-3 pb-2 pt-10 text-meta text-white'
        : 'text-meta text-muted-foreground'
    "
  >
    {{ kind === 'photo' ? t('common.imageCredit.photo') : t('common.textCredit.text') }}
    <a :href="credit.sourceUrl" class="underline underline-offset-2" rel="noopener" target="_blank">
      {{ credit.attribution }}
    </a>
    <template v-if="credit.licenceUrl">
      ·
      <a
        :href="credit.licenceUrl"
        class="underline underline-offset-2"
        rel="noopener"
        target="_blank"
      >
        {{ credit.licenceLabel }}
      </a>
    </template>
    <template v-else> · {{ credit.licenceLabel }} </template>
  </p>
</template>
