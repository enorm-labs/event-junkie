<script lang="ts" setup>
/**
 * Shared shell for the pages under `/legal/*`: one column width, one heading rhythm, one place
 * for the "last reviewed" line. Deliberately a hand-rolled prose scale on the same
 * `mx-auto max-w-3xl` measure the other static views use, rather than a typography plugin —
 * these are four pages of body copy, not a CMS.
 */
import { LAST_REVIEWED } from '@/lib/legal'
import { useI18n } from 'vue-i18n'

defineProps<{
  title: string
  /** Shown under the heading — one sentence on what this page is for. */
  intro?: string
  /** Legal pages state when they were last checked against reality; the notices page does not. */
  showReviewDate?: boolean
  /**
   * Renders the sentence naming the German version as the authoritative one.
   *
   * A prop rather than a line typed into each page: it belongs on the imprint and the privacy
   * notice, in *both* languages, which is four places to forget it. Forgetting it is the whole
   * risk — two language versions with no stated precedence is worse than one language.
   * See docs/LEGAL.md §6.1.
   */
  showAuthoritativeVersion?: boolean
}>()

const { t } = useI18n()
</script>

<template>
  <main class="mx-auto max-w-3xl space-y-6 p-4 sm:p-8">
    <header class="space-y-2">
      <h1 class="text-3xl font-bold tracking-tight">{{ title }}</h1>
      <p v-if="intro" class="text-muted-foreground">{{ intro }}</p>
      <p v-if="showReviewDate" class="text-sm text-muted-foreground">
        {{ t('legal.lastReviewed') }} <time :datetime="LAST_REVIEWED">{{ LAST_REVIEWED }}</time>
      </p>
      <p v-if="showAuthoritativeVersion" class="text-sm text-muted-foreground">
        {{ t('legal.authoritativeVersion') }}
      </p>
    </header>

    <!-- `[&_x]` selectors rather than a class on every element: the page bodies below are plain
         semantic markup, which keeps them readable as documents and easy to translate later. -->
    <div
      class="space-y-6 text-muted-foreground [&_a]:text-foreground [&_a]:underline [&_a]:underline-offset-4 [&_dt]:font-medium [&_dt]:text-foreground [&_h2]:text-xl [&_h2]:font-semibold [&_h2]:tracking-tight [&_h2]:text-foreground [&_h3]:font-medium [&_h3]:text-foreground [&_li]:my-1 [&_ol]:list-decimal [&_ol]:space-y-1 [&_ol]:pl-6 [&_p]:my-2 [&_section]:space-y-2 [&_ul]:list-disc [&_ul]:space-y-1 [&_ul]:pl-6"
    >
      <slot />
    </div>
  </main>
</template>
