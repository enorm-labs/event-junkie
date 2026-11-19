<script setup lang="ts">
/**
 * One Share menu: the native share sheet where the browser has one, and Copy link (#2564). The
 * event page adds its calendar entries through the slot; the detail pages use it bare (#5291).
 * The outcome goes to `status`, so the page places the live region where its layout has room.
 */
import { useI18n } from 'vue-i18n'
import { Link as LinkIcon, Send, Share2 } from '@lucide/vue'
import { Button } from '@/components/ui/button'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { canShareNatively, copyLink, shareLink, type ShareOutcome } from '@/lib/share'

const props = defineProps<{ title?: string | null; url: string }>()
const status = defineModel<string>('status', { default: '' })

const { t } = useI18n()
const nativeShare = canShareNatively()

function announce(outcome: ShareOutcome): void {
  status.value =
    outcome === 'copied'
      ? t('common.share.copied')
      : outcome === 'failed'
        ? t('common.share.copyFailed', { url: props.url })
        : ''
}

async function share(): Promise<void> {
  announce(await shareLink({ title: props.title ?? undefined, url: props.url }))
}

async function copy(): Promise<void> {
  announce(await copyLink(props.url))
}
</script>

<template>
  <DropdownMenu>
    <DropdownMenuTrigger as-child>
      <Button variant="outline">
        <Share2 aria-hidden="true" />
        {{ t('common.share.share') }}
      </Button>
    </DropdownMenuTrigger>
    <DropdownMenuContent align="start">
      <DropdownMenuItem v-if="nativeShare" @select="share">
        <Send aria-hidden="true" />
        {{ t('common.share.nativeShare') }}
      </DropdownMenuItem>
      <DropdownMenuItem @select="copy">
        <LinkIcon aria-hidden="true" />
        {{ t('common.share.copyLink') }}
      </DropdownMenuItem>
      <slot />
    </DropdownMenuContent>
  </DropdownMenu>
</template>
