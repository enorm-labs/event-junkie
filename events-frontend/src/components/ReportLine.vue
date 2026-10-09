<script lang="ts" setup>
/**
 * The line that closes every detail page: a prefilled mail that names the page, so a visitor
 * reports wrong data without copying anything (#378).
 */
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import type { Locale } from '@/i18n/locales'
import { feedbackMailto } from '@/lib/feedback'
import { CONTROLLER } from '@/lib/legal'
import { canonicalUrl } from '@/lib/seo'

const props = defineProps<{
  /** The question before the address, e.g. "Something wrong with this venue? Report it by email:". */
  text: string
  /** The entity's name, for the mail subject. */
  title: string
  /** The page's path without the locale, e.g. `/venues/gretchen`. */
  path: string
}>()

const { t, locale } = useI18n()

const href = computed(() =>
  feedbackMailto(
    t('detail.reportSubject', { title: props.title }),
    t('detail.reportBody', { url: canonicalUrl(locale.value as Locale, props.path) }),
  ),
)
</script>

<template>
  <!-- The address is the link text, so a visitor without a mail client can still copy it. -->
  <p class="text-body text-muted-foreground">
    {{ text }}
    <a :href="href" class="text-foreground underline underline-offset-4">{{ CONTROLLER.email }}</a>
  </p>
</template>
