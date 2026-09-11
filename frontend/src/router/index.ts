import { createRouter, createWebHashHistory } from 'vue-router'
import type { RouteRecordRaw } from 'vue-router'

const APP_TITLE = '智能代码分析工具'

/** 侧边栏菜单项：由路由表生成，`meta.hideMenu` 的项不出现（如项目详情） */
export interface MenuItem {
  path: string
  title: string
}

const appRoutes: RouteRecordRaw[] = [
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

/**
 * 菜单由**路由表**推导，而不是另写一份硬编码数组。
 *
 * 原先 `BasicLayout` 里手写菜单项、`meta.title`/`hideMenu` 无人消费 —— 结果是
 * 加路由不会自动出现在菜单里，`meta` 也成了死配置。
 */
export const menuItems: MenuItem[] = appRoutes
  .filter((route) => route.meta && !route.meta.hideMenu)
  .map((route) => ({ path: `/${route.path}`, title: String(route.meta!.title) }))

/**
 * 当前路径对应哪个菜单项。
 *
 * 详情页 `/projects/123` 也要归到 `/projects` —— 原先 BasicLayout 直接用
 * `[route.path]` 比对，进详情页后**侧边栏没有任何高亮**，用户失去方位感。
 * 抽成纯函数是为了能直接单测（不必挂载整棵树）。
 */
export function activeMenuKey(path: string, items: MenuItem[] = menuItems): string {
  const hit = items.find((item) => path === item.path || path.startsWith(`${item.path}/`))
  return hit ? hit.path : ''
}

const routes: RouteRecordRaw[] = [
  {
    path: '/',
    component: () => import('@/layouts/BasicLayout.vue'),
    redirect: '/projects',
    children: appRoutes
  }
]

const router = createRouter({
  history: createWebHashHistory(),
  routes
})

/** 标签页标题跟随路由（原先固定为 index.html 里那一句，切页面完全没反应） */
router.afterEach((to) => {
  const title = to.meta?.title
  document.title = title ? `${title} · ${APP_TITLE}` : APP_TITLE
})

export default router
