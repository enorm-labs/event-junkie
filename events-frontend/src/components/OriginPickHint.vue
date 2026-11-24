<script lang="ts" setup>
/** What to do while a point is picked on the map, with the map's centre as a one-click choice. */
import { useI18n } from 'vue-i18n'
import { Button } from '@/components/ui/button'

const emit = defineEmits<{
  center: []
}>()

const { t } = useI18n()
</script>

<template>
  <p class="flex flex-wrap items-center gap-2 text-body text-muted-foreground">
    {{ t('map.near.pickHint') }}
    <Button size="xs" type="button" variant="link" @click="emit('center')">
      {{ t('map.near.useCentre') }}
    </Button>
  </p>
</template>
