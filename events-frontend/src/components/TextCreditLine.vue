<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import type { ImageCredit } from '@/lib/imageCredit'

/**
 * The credit for a licensed description, an artist's Wikipedia lead (#1837). CC BY-SA asks for the
 * source, the licence and a link, the same three parts `ImageCreditLine` shows for a picture.
 */
defineProps<{ credit: ImageCredit }>()

const { t } = useI18n()
</script>

<template>
  <p class="text-xs text-muted-foreground">
    {{ t('common.textCredit.text') }}
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
