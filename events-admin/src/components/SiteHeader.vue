<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'

import { Badge } from '@/components/ui/badge'
import { Separator } from '@/components/ui/separator'
import { SidebarTrigger } from '@/components/ui/sidebar'

const route = useRoute()
const title = computed(() => String(route.meta.title ?? ''))

// The mode the dev server was started with, which names the cluster behind the proxy.
const environment = import.meta.env.MODE
</script>

<template>
  <header class="flex h-12 shrink-0 items-center gap-2 border-b">
    <div class="flex w-full items-center gap-1 px-4 lg:gap-2 lg:px-6">
      <SidebarTrigger class="-ml-1" />
      <Separator class="mx-2 data-[orientation=vertical]:h-4" orientation="vertical" />
      <h1 class="text-base font-medium">{{ title }}</h1>
      <Badge :variant="environment === 'production' ? 'destructive' : 'secondary'" class="ml-auto">
        {{ environment }}
      </Badge>
    </div>
  </header>
</template>
