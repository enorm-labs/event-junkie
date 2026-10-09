import { createRouter, createWebHistory } from 'vue-router'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/sources' },
    {
      path: '/sources',
      name: 'sources',
      component: () => import('@/views/SourcesView.vue'),
      meta: { title: 'Event sources' },
    },
    {
      path: '/worklist',
      name: 'worklist',
      component: () => import('@/views/WorklistView.vue'),
      meta: { title: 'Data-quality worklist' },
    },
    {
      path: '/events/new',
      name: 'new-event',
      component: () => import('@/views/NewEventView.vue'),
      meta: { title: 'New event' },
    },
  ],
})

export default router
