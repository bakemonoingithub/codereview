import { createRouter, createWebHashHistory } from 'vue-router'
import type { RouteRecordRaw } from 'vue-router'

const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'login',
    component: () => import('@/views/LoginView.vue'),
    meta: { title: '登录', hideMenu: true }
  },
  {
    path: '/',
    component: () => import('@/layouts/BasicLayout.vue'),
    redirect: '/projects',
    children: [
      {
        path: 'projects',
        name: 'projects',
        component: () => import('@/views/ProjectList.vue'),
        meta: { title: '项目' }
      },
      {
        path: 'projects/:id',
        name: 'project-detail',
        component: () => import('@/views/ProjectDetail.vue'),
        meta: { title: '项目详情', hideMenu: true }
      },
      {
        path: 'models',
        name: 'models',
        component: () => import('@/views/ModelConfigList.vue'),
        meta: { title: '模型' }
      },
      {
        path: 'strategies',
        name: 'strategies',
        component: () => import('@/views/StrategyList.vue'),
        meta: { title: '策略' }
      },
      {
        path: 'prompts',
        name: 'prompts',
        component: () => import('@/views/PromptList.vue'),
        meta: { title: '提示词' }
      }
    ]
  }
]

const router = createRouter({
  history: createWebHashHistory(),
  routes
})

export default router
