<script setup lang="ts">
import {
  Building2,
  CalendarPlus,
  CalendarSearch,
  Database,
  GlobeX,
  Inbox,
  ListChecks,
  MicVocal,
  ShieldCheck,
  Sparkles,
} from '@lucide/vue'
import { computed } from 'vue'
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
const NAV = [
  { title: 'Sources', to: '/sources', icon: Database },
  { title: 'Events', to: '/events', icon: CalendarSearch },
  { title: 'Worklist', to: '/worklist', icon: ListChecks },
  { title: 'Suggested fixes', to: '/suggestions', icon: Sparkles },
  { title: 'Instagram drafts', to: '/drafts', icon: Inbox },
  { title: 'Artists to review', to: '/artists', icon: MicVocal },
  { title: 'Venues to review', to: '/venues/review', icon: GlobeX },
  { title: 'Venues', to: '/venues', icon: Building2 },
  { title: 'New event', to: '/events/new', icon: CalendarPlus },
]

const route = useRoute()

// The longest entry the path lies under, so /events/new marks "New event" and /venues/review
// "Venues to review".
const active = computed(
  () =>
    NAV.map((item) => item.to)
      .filter((to) => route.path === to || route.path.startsWith(`${to}/`))
      .sort((a, b) => b.length - a.length)[0],
)
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
              <SidebarMenuButton as-child :is-active="item.to === active" :tooltip="item.title">
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
