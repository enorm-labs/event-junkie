<script lang="ts" setup>
import { ChevronLeft, ChevronRight } from '@lucide/vue'
import { computed } from 'vue'
import { type RouteLocationRaw, RouterLink } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { Button } from '@/components/ui/button'
import { pageWindow } from '@/lib/pageWindow'

/**
 * Previous · numbered pages · next, the pager the venue and promoter lists share. Every control is
 * a link from `pageLink`, so a page opens in a new tab and a crawler follows it.
 *
 * RouterLink would mark every link here `aria-current`: it compares the path, not the query, so
 * each link sets the attribute itself.
 *
 * It renders nothing on a single page, so a caller passes the numbers `usePagedList` returns and
 * leaves the decision here. A phone has no room for the numbers and reads "Page n of m" instead.
 */
const props = defineProps<{
  currentPage: number
  totalPages: number
  pageLink: (page: number) => RouteLocationRaw
}>()

const { t } = useI18n()

const slots = computed(() => pageWindow(props.currentPage, props.totalPages))
const hasPrevious = computed(() => props.currentPage > 0)
const hasNext = computed(() => props.currentPage < props.totalPages - 1)
</script>

<template>
  <nav
    v-if="totalPages > 1"
    :aria-label="t('common.pagination.label')"
    class="flex items-center justify-between gap-2 pt-2"
  >
    <Button v-if="hasPrevious" as-child variant="outline">
      <RouterLink :aria-current="undefined" :to="pageLink(currentPage - 1)" rel="prev">
        <ChevronLeft aria-hidden="true" />
        {{ t('common.actions.previous') }}
      </RouterLink>
    </Button>
    <Button v-else disabled variant="outline">
      <ChevronLeft aria-hidden="true" />
      {{ t('common.actions.previous') }}
    </Button>

    <span class="text-body text-muted-foreground sm:hidden">
      {{ t('common.pagination.pageOf', { current: currentPage + 1, total: totalPages }) }}
    </span>
    <ol class="hidden items-center gap-1 sm:flex">
      <template v-for="slot in slots" :key="'gap' in slot ? slot.gap : slot.page">
        <li v-if="'gap' in slot" aria-hidden="true" class="px-1 text-body text-muted-foreground">
          …
        </li>
        <li v-else>
          <Button
            as-child
            :variant="slot.page === currentPage ? 'default' : 'ghost'"
            class="min-w-8"
          >
            <RouterLink
              :aria-current="slot.page === currentPage ? 'page' : undefined"
              :aria-label="t('common.pagination.page', { page: slot.page + 1 })"
              :to="pageLink(slot.page)"
            >
              {{ slot.page + 1 }}
            </RouterLink>
          </Button>
        </li>
      </template>
    </ol>

    <Button v-if="hasNext" as-child variant="outline">
      <RouterLink :aria-current="undefined" :to="pageLink(currentPage + 1)" rel="next">
        {{ t('common.actions.next') }}
        <ChevronRight aria-hidden="true" />
      </RouterLink>
    </Button>
    <Button v-else disabled variant="outline">
      {{ t('common.actions.next') }}
      <ChevronRight aria-hidden="true" />
    </Button>
  </nav>
</template>
