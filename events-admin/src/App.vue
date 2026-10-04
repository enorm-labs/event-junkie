<script setup lang="ts">
import { RouterView } from 'vue-router'

import AppSidebar from '@/components/AppSidebar.vue'
import SiteHeader from '@/components/SiteHeader.vue'
import { SidebarInset, SidebarProvider } from '@/components/ui/sidebar'
</script>

<!-- The layout of shadcn-vue's dashboard-01 block, without its demo charts, cards and user menu. -->
<template>
  <SidebarProvider>
    <AppSidebar />
    <!-- min-w-0: a flex child grows to its widest content, and the table would widen the page. -->
    <SidebarInset class="min-w-0">
      <SiteHeader />
      <main class="flex min-w-0 flex-1 flex-col gap-4 p-4 lg:p-6">
        <RouterView />
      </main>
    </SidebarInset>
  </SidebarProvider>
</template>
