<script lang="ts" setup>
/**
 * A prefilled mail that names the page, so a visitor writes without copying anything: the data
 * report that closes every detail page (#378), and the call for a night's set times (#4631).
 */
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import type { Locale } from '@/i18n/locales'
import { feedbackMailto } from '@/lib/feedback'
import { CONTROLLER } from '@/lib/legal'
import { canonicalUrl } from '@/lib/seo'
import { UNDERLINED_LINK_CLASS } from '@/lib/utils'

const props = defineProps<{
  /** The question before the address, e.g. "Something wrong with this venue? Report it by email:". */
  text: string
  /** The entity's name, for the mail subject. */
  title: string
  /** The page's path without the locale, e.g. `/venues/gretchen`. */
  path: string
  /** Message keys of the mail's subject (with `title`) and body (with `url`); a data report by default. */
  subjectKey?: string
  bodyKey?: string
}>()

const { t, locale } = useI18n()

const href = computed(() =>
  feedbackMailto(
    t(props.subjectKey ?? 'detail.reportSubject', { title: props.title }),
    t(props.bodyKey ?? 'detail.reportBody', {
      url: canonicalUrl(locale.value as Locale, props.path),
    }),
  ),
)
</script>

<template>
  <!-- The address is the link text, so a visitor without a mail client can still copy it. -->
  <p class="text-body text-muted-foreground">
    {{ text }}
    <a :href="href" :class="[UNDERLINED_LINK_CLASS, 'text-foreground']">{{ CONTROLLER.email }}</a>
  </p>
</template>
