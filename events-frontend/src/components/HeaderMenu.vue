<script lang="ts" setup>
/**
 * The header's section links and GitHub link on a phone (#2291): a burger and a side sheet. Rendered
 * without a portal, so the sheet stays inside the header's navigation landmark and its links are
 * still "Main" navigation to assistive tech and to the e2e selectors.
 */
import { ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { RouterLink, useRoute } from 'vue-router'
import {
  DialogClose,
  DialogContent,
  DialogOverlay,
  DialogRoot,
  DialogTitle,
  DialogTrigger,
} from 'reka-ui'
import { Menu, X } from '@lucide/vue'
import { Button } from '@/components/ui/button'
import BaseBadge from '@/components/BaseBadge.vue'
import GitHubMark from '@/components/GitHubMark.vue'
import { useLocalePath } from '@/composables/useLocalePath'
import { REPOSITORY_URL } from '@/lib/links'

defineProps<{ links: { to: string; label: string }[] }>()

const { t } = useI18n()
const localePath = useLocalePath()
const open = ref(false)

// A link to another page closes the sheet through the route change; a tap on the current page's
// own link changes nothing, so the link closes it too.
const route = useRoute()
watch(
  () => route.fullPath,
  () => {
    open.value = false
  },
)
</script>

<template>
  <!-- A real element at the root: DialogRoot renders none, so a `class` from App.vue (its
       `md:hidden`) was dropped and the button showed on desktop too. -->
  <div>
    <DialogRoot v-model:open="open">
      <DialogTrigger as-child>
        <!-- Labelled, not icon-only: a bare burger beside the icon buttons reads as one more setting. -->
        <Button variant="outline">
          <Menu data-icon="inline-start" />
          {{ t('common.nav.menu') }}
        </Button>
      </DialogTrigger>
      <DialogOverlay class="fixed inset-0 z-40 bg-background/80" />
      <DialogContent
        :aria-describedby="undefined"
        class="fixed inset-y-0 right-0 z-50 flex w-3/4 max-w-xs flex-col gap-6 border-l border-border bg-background p-4 outline-hidden"
      >
        <div class="flex items-center justify-between">
          <div class="flex items-center gap-2">
            <DialogTitle class="text-meta text-muted-foreground">
              {{ t('common.nav.menu') }}
            </DialogTitle>
            <!-- The header drops its beta badge below 360px (#2666); this is the same link there. -->
            <RouterLink
              :aria-label="t('common.nav.betaLabel')"
              :title="t('common.nav.betaLabel')"
              :to="localePath('/about#beta')"
              class="rounded-full transition-opacity hover:opacity-80 min-[360px]:hidden"
              @click="open = false"
            >
              <BaseBadge variant="outline">{{ t('common.nav.beta') }}</BaseBadge>
            </RouterLink>
          </div>
          <DialogClose as-child>
            <Button
              :aria-label="t('common.nav.closeMenu')"
              :title="t('common.nav.closeMenu')"
              size="icon"
              variant="ghost"
            >
              <X />
            </Button>
          </DialogClose>
        </div>
        <ul class="flex flex-col">
          <li v-for="link in links" :key="link.to">
            <RouterLink
              :to="link.to"
              class="block py-2 text-lede text-muted-foreground hover:text-foreground [&.router-link-exact-active]:text-foreground"
              @click="open = false"
            >
              {{ link.label }}
            </RouterLink>
          </li>
        </ul>
        <!-- The header row has no room for it below `md`, so it lives here. -->
        <a
          :href="REPOSITORY_URL"
          :title="t('common.nav.sourceOnGitHub')"
          class="flex items-center gap-2 border-t border-border pt-4 text-body text-muted-foreground hover:text-foreground"
          rel="noopener"
          target="_blank"
        >
          <GitHubMark class="size-4 shrink-0" />
          {{ t('common.nav.sourceOnGitHub') }}
        </a>
      </DialogContent>
    </DialogRoot>
  </div>
</template>
