<script lang="ts" setup>
/**
 * The header's display preferences (#2568): one icon button opening the theme and the events view.
 * Both stay persisted and applied before paint by the inline script in index.html; only the place
 * they are switched from is here. The header keeps this mounted, so it follows an OS switch.
 */
import { onBeforeUnmount } from 'vue'
import { useI18n } from 'vue-i18n'
import { Settings2 } from '@lucide/vue'
import DisplaySettingsGroups from '@/components/DisplaySettingsGroups.vue'
import { Button } from '@/components/ui/button'
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover'
import { applyTheme, osDarkQuery, storedTheme } from '@/lib/theme'

const { t } = useI18n()

const osDark = osDarkQuery()
const onOsChange = () => {
  if (storedTheme() === 'system') applyTheme('system')
}
osDark?.addEventListener('change', onOsChange)
onBeforeUnmount(() => osDark?.removeEventListener('change', onOsChange))
</script>

<template>
  <Popover>
    <PopoverTrigger as-child>
      <Button
        :aria-label="t('common.display.label')"
        :title="t('common.display.label')"
        size="icon"
        variant="outline"
      >
        <Settings2 />
      </Button>
    </PopoverTrigger>
    <PopoverContent align="end" class="w-auto">
      <DisplaySettingsGroups />
    </PopoverContent>
  </Popover>
</template>
