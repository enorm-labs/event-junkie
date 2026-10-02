<script lang="ts" setup>
import { useI18n } from 'vue-i18n'
import { Button } from '@/components/ui/button'

/**
 * Previous · "Page n of m" · next, the pager the three list views share.
 *
 * It renders nothing on a single page, so a caller passes the numbers `usePagedList` returns and
 * leaves the decision here.
 */
defineProps<{ currentPage: number; totalPages: number }>()

const emit = defineEmits<{ goto: [page: number] }>()

const { t } = useI18n()
</script>

<template>
  <div v-if="totalPages > 1" class="flex items-center justify-between gap-3 pt-2">
    <Button :disabled="currentPage <= 0" variant="outline" @click="emit('goto', currentPage - 1)">
      {{ t('common.actions.previous') }}
    </Button>
    <span class="text-body text-muted-foreground">
      {{ t('common.pagination.pageOf', { current: currentPage + 1, total: totalPages }) }}
    </span>
    <Button
      :disabled="currentPage >= totalPages - 1"
      variant="outline"
      @click="emit('goto', currentPage + 1)"
    >
      {{ t('common.actions.next') }}
    </Button>
  </div>
</template>
