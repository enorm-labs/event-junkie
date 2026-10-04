<script lang="ts" setup>
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { RouterLink, RouterView, useRouter } from 'vue-router'
import { Button } from '@/components/ui/button'
import AppFooter from '@/components/AppFooter.vue'
import BaseBadge from '@/components/BaseBadge.vue'
import BrandLogo from '@/components/BrandLogo.vue'
import DisplaySettings from '@/components/DisplaySettings.vue'
import GitHubMark from '@/components/GitHubMark.vue'
import GlobalSearch from '@/components/GlobalSearch.vue'
import HeaderMenu from '@/components/HeaderMenu.vue'
import LocaleMenu from '@/components/LocaleMenu.vue'
import { pageTitle } from '@/composables/usePageMeta'
import { REPOSITORY_URL } from '@/lib/links'
import { useLocalePath } from '@/composables/useLocalePath'

// Screen-reader route announcer: client-side navigations do not move focus or re-read the page,
// so the title is mirrored into an aria-live region on each change. The initial load is skipped
// (router.isReady) because assistive tech already announces the document then.
const { t } = useI18n()

const announcement = ref('')
const router = useRouter()
let ready = false
router.isReady().then(() => {
  ready = true
})
watch(pageTitle, (title) => {
  if (ready) announcement.value = title
})

// One string behind the beta badge's tooltip and accessible name: "beta" alone is a useless
// link name out of context.
const betaLabel = computed(() => t('common.nav.betaLabel'))

const localePath = useLocalePath()

// One list for the inline row and the phone sheet, so the two cannot drift.
const sectionLinks = computed(() =>
  (['events', 'calendar', 'map', 'venues', 'promoters', 'about'] as const).map((section) => ({
    to: localePath(`/${section}`),
    label: t(`common.nav.${section}`),
  })),
)
</script>

<template>
  <div class="flex min-h-screen flex-col bg-background text-foreground">
    <!-- Announces route changes to screen readers; visually hidden. -->
    <div aria-atomic="true" aria-live="polite" class="sr-only" role="status">
      {{ announcement }}
    </div>

    <!-- WCAG 2.4.1 Bypass Blocks: hidden until focused, the first focusable element, targeting the
         wrapper below rather than each view's <main> so there is one target to keep in sync. -->
    <a
      class="sr-only focus:not-sr-only focus:absolute focus:top-2 focus:left-2 focus:z-50 focus:rounded-md focus:bg-background focus:px-4 focus:py-2 focus:text-foreground focus:ring-3 focus:ring-ring/50"
      href="#main-content"
    >
      {{ t('common.skipToContent') }}
    </a>

    <!-- Sticky so the navigation stays in reach on a long feed (#2321). Opaque, so cards do not show
         through, and z-30: below HeaderMenu's overlay and sheet and the skip link. -->
    <header class="sticky top-0 z-30 border-b border-border bg-background">
      <!-- Below `lg` the links drop to a second line: one row needs about 810px in German, and the
           guard in e2e/smoke.spec.ts checks the widths in between in both languages. -->
      <!-- Named because the footer contributes a second navigation landmark; e2e selectors address
           it by this name. -->
      <nav
        :aria-label="t('common.nav.label')"
        class="mx-auto flex max-w-5xl flex-wrap items-center gap-x-4 gap-y-3 p-4 text-body font-medium lg:flex-nowrap lg:gap-6"
      >
        <RouterLink
          :to="localePath('/')"
          class="shrink-0 rounded-sm transition-opacity hover:opacity-80"
        >
          <BrandLogo />
        </RouterLink>

        <!-- A link rather than a tooltip-only marker: a `title` is invisible on touch devices.
             Targets the About page's #beta section. -->
        <RouterLink
          :aria-label="betaLabel"
          :title="betaLabel"
          :to="localePath('/about#beta')"
          class="mr-2 rounded-full transition-opacity hover:opacity-80"
        >
          <BaseBadge variant="outline">{{ t('common.nav.beta') }}</BaseBadge>
        </RouterLink>
        <!-- Order is deliberate: /events, /calendar and /map are three views of the same data, /venues and
             /promoters the other entities, /about is meta. e2e/smoke.spec.ts pins it. -->
        <!-- The row wraps below `lg` so "Über das Projekt" never breaks across two lines. Below `md`
             the links move into HeaderMenu's sheet (#2291). -->
        <div
          class="order-last hidden w-full flex-wrap items-center gap-x-4 gap-y-1 md:flex lg:order-none lg:w-auto lg:flex-nowrap lg:gap-6"
        >
          <RouterLink
            v-for="link in sectionLinks"
            :key="link.to"
            :to="link.to"
            class="whitespace-nowrap text-muted-foreground hover:text-foreground [&.router-link-exact-active]:text-foreground"
          >
            {{ link.label }}
          </RouterLink>
        </div>
        <div class="ml-auto flex items-center gap-2">
          <GlobalSearch />
          <LocaleMenu />
          <!-- `title` is the hover tooltip only; `aria-label` wins for the accessible name. Below
               `md` the link is in HeaderMenu's sheet. -->
          <Button
            :aria-label="t('common.nav.sourceOnGitHub')"
            :href="REPOSITORY_URL"
            :title="t('common.nav.sourceOnGitHub')"
            as="a"
            class="hidden md:inline-flex"
            rel="noopener"
            size="icon"
            target="_blank"
            variant="outline"
          >
            <GitHubMark />
          </Button>
          <DisplaySettings />
          <HeaderMenu :links="sectionLinks" class="md:hidden" />
        </div>
      </nav>
    </header>

    <!-- `tabindex="-1"` makes this focusable by the skip link without adding it to the tab order.
         A screen tall, so the footer never paints above the fold. Otherwise it sits there while a
         view's chunk and data load, then drops, and that one move is the page's layout shift (#1207). -->
    <div id="main-content" class="min-h-screen" tabindex="-1">
      <RouterView />
    </div>

    <AppFooter />
  </div>
</template>
