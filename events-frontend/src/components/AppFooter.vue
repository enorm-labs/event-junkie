<script lang="ts" setup>
/**
 * Site footer: provenance, project links and the copyright line.
 *
 * Every link here resolves: a footer with dead links is worse
 * than no footer (docs/LEGAL.md §2).
 *
 * The disclaimer sits here rather than only on a legal page because it is the single most useful
 * sentence for a user of an aggregator, and nobody clicks through to read it (§7.6).
 */
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { RouterLink } from 'vue-router'
import BrandLogo from '@/components/BrandLogo.vue'
import LocaleSwitcher from '@/components/LocaleSwitcher.vue'
import { useAppMeta } from '@/composables/useAppMeta'
import {
  commitUrl,
  CONTRIBUTING_URL,
  LICENSE_URL,
  NEW_ISSUE_URL,
  RELEASES_URL,
  releaseTagUrl,
  REPOSITORY_URL,
} from '@/lib/links'
import { useLocalePath } from '@/composables/useLocalePath'

// Hardcoded rather than `new Date().getFullYear()`: a clock-derived year makes an archived page
// claim a copyright it never carried, and it would make any snapshot test depend on the date (§3).
const COPYRIGHT_YEAR = 2026

const { t } = useI18n()

const linkClass = 'text-muted-foreground hover:text-foreground'

const { meta } = useAppMeta()

/**
 * A release tag exists only for a released version, so only those are linked. Everything else —
 * `dev` (no build info: the IDE and `bootRun`), `0.1.1-SNAPSHOT` (a local build), and
 * `0.1.1-snapshot.20260817180146.g787d7d0` (what every deployed build actually reports) — renders as
 * plain text.
 *
 * **The version is still displayed in all of those cases; only the link is withheld.** Which build
 * staging is running is the whole reason this line exists.
 *
 * `releaseTagUrl` decides, by testing for a `major.minor.patch` triple. This used to ask "does it
 * contain `-SNAPSHOT`?" and got it wrong for three months, because that spelling lives only in
 * `gradle.properties` and never reaches a browser (#502).
 */
const releaseUrl = computed(() => releaseTagUrl(meta.value?.version))

const localePath = useLocalePath()
</script>

<template>
  <footer class="mt-16 border-t border-border">
    <div class="mx-auto max-w-5xl px-4 py-10">
      <div class="grid gap-8 sm:grid-cols-2 md:grid-cols-3">
        <div class="space-y-3">
          <BrandLogo always-show-wordmark />
          <p class="text-sm text-muted-foreground">{{ t('footer.tagline') }}</p>
          <!-- "Alle Angaben ohne Gewähr", in the register the brand actually speaks (§7.6). -->
          <p class="max-w-prose text-sm text-muted-foreground">{{ t('footer.disclaimer') }}</p>
        </div>

        <nav aria-labelledby="footer-project-heading" class="space-y-3 text-sm">
          <h2 id="footer-project-heading" class="font-medium text-foreground">
            {{ t('footer.project') }}
          </h2>
          <ul class="space-y-2">
            <li>
              <a :class="linkClass" :href="REPOSITORY_URL" rel="noopener" target="_blank">
                {{ t('footer.sourceOnGitHub') }}
              </a>
            </li>
            <li>
              <a :class="linkClass" :href="NEW_ISSUE_URL" rel="noopener" target="_blank">
                {{ t('footer.reportAnIssue') }}
              </a>
            </li>
            <li>
              <a :class="linkClass" :href="CONTRIBUTING_URL" rel="noopener" target="_blank">
                {{ t('footer.contributing') }}
              </a>
            </li>
            <li>
              <a :class="linkClass" :href="RELEASES_URL" rel="noopener" target="_blank">
                {{ t('footer.changelog') }}
              </a>
            </li>
          </ul>
        </nav>

        <nav aria-labelledby="footer-legal-heading" class="space-y-3 text-sm">
          <h2 id="footer-legal-heading" class="font-medium text-foreground">
            {{ t('footer.legal') }}
          </h2>
          <ul class="space-y-2">
            <li>
              <RouterLink :class="linkClass" :to="localePath('/legal/imprint')">{{
                t('footer.imprint')
              }}</RouterLink>
            </li>
            <li>
              <RouterLink :class="linkClass" :to="localePath('/legal/privacy')">{{
                t('footer.privacy')
              }}</RouterLink>
            </li>
            <li>
              <RouterLink :class="linkClass" :to="localePath('/legal/notices')">{{
                t('footer.notices')
              }}</RouterLink>
            </li>
          </ul>
        </nav>
      </div>

      <div
        class="mt-8 border-t border-border pt-6 text-sm text-muted-foreground sm:flex sm:items-center sm:justify-between"
      >
        <!-- Two clauses, deliberately: the copyright covers this site's own design and text, the
             licence covers the code. Event data is neither ours to licence nor covered here (§3). -->
        <p>
          {{ t('footer.copyright', { year: COPYRIGHT_YEAR }) }} ·
          <a :class="linkClass" :href="LICENSE_URL" rel="noopener" target="_blank">
            {{ t('footer.licence') }}
          </a>
        </p>

        <LocaleSwitcher class="mt-4 sm:mt-0" />

        <!-- Renders nothing until /meta resolves. A version is worth exactly one thing — telling
             you what someone was running when they report a bug — so it must never cost a layout
             shift or an error state to obtain (§4.4). -->
        <p v-if="meta?.version" class="mt-4 font-mono text-xs sm:mt-0" data-testid="app-version">
          <component
            :is="releaseUrl ? 'a' : 'span'"
            :class="releaseUrl ? linkClass : ''"
            v-bind="releaseUrl ? { href: releaseUrl, rel: 'noopener', target: '_blank' } : {}"
          >
            v{{ meta.version }}
          </component>
          <template v-if="meta.commit && meta.commitShort">
            ·
            <a
              :class="linkClass"
              :href="commitUrl(meta.commit)"
              :title="`Built from commit ${meta.commit}${meta.buildTime ? ` on ${meta.buildTime}` : ''}`"
              rel="noopener"
              target="_blank"
            >
              {{ meta.commitShort }}
            </a>
          </template>
        </p>
      </div>
    </div>
  </footer>
</template>
