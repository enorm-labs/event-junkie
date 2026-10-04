<script setup lang="ts">
import { Database, ShieldCheck } from '@lucide/vue'
import { RouterLink, useRoute } from 'vue-router'

import {
  Sidebar,
  SidebarContent,
  SidebarGroup,
  SidebarGroupContent,
  SidebarHeader,
  SidebarMenu,
  SidebarMenuButton,
  SidebarMenuItem,
} from '@/components/ui/sidebar'

// One entry per admin page. The pages #342, #343, #345 and #347 add go here.
const NAV = [{ title: 'Sources', to: '/sources', icon: Database }]

const route = useRoute()
</script>

<template>
  <Sidebar collapsible="offcanvas">
    <SidebarHeader>
      <SidebarMenu>
        <SidebarMenuItem>
          <SidebarMenuButton as-child class="data-[slot=sidebar-menu-button]:p-1.5!">
            <RouterLink to="/">
              <ShieldCheck class="size-5!" />
              <span class="text-base font-semibold">Event Junkie admin</span>
            </RouterLink>
          </SidebarMenuButton>
        </SidebarMenuItem>
      </SidebarMenu>
    </SidebarHeader>
    <SidebarContent>
      <SidebarGroup>
        <SidebarGroupContent>
          <SidebarMenu>
            <SidebarMenuItem v-for="item in NAV" :key="item.to">
              <SidebarMenuButton
                as-child
                :is-active="route.path.startsWith(item.to)"
                :tooltip="item.title"
              >
                <RouterLink :to="item.to">
                  <component :is="item.icon" />
                  <span>{{ item.title }}</span>
                </RouterLink>
              </SidebarMenuButton>
            </SidebarMenuItem>
          </SidebarMenu>
        </SidebarGroupContent>
      </SidebarGroup>
    </SidebarContent>
  </Sidebar>
</template>
