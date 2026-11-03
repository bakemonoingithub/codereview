// @vitest-environment happy-dom
// 需要 DOM：导入 @/router 会 createWebHashHistory()（读 location）
import { beforeEach, describe, expect, it } from 'vitest'
import router from '@/router'

/**
 * 旧项目详情地址不再装载已退出的页面，统一转到现行项目列表。
 * 路径里的数字只是旧收藏地址，不解释成现行项目 ID。
 */
describe('旧项目地址重定向', () => {
  beforeEach(async () => {
    await router.replace('/reviews')
    await router.isReady()
  })

  it('路由记录只重定向，不再挂旧项目详情组件', () => {
    const record = router.getRoutes().find((item) => item.path === '/projects/:id')
    expect(record).toBeTruthy()
    expect(record?.redirect).toBe('/projects')
    expect(record?.components).toBeUndefined()
  })

  it('直达数字 ID 落到 /projects，地址栏也不再保留该 ID', async () => {
    await router.replace('/projects/42')
    await router.isReady()
    const current = router.currentRoute.value
    expect(current.path).toBe('/projects')
    expect(current.name).toBe('projects')
    expect(current.params).toEqual({})
    expect(current.query).toEqual({})
    expect(current.fullPath).toBe('/projects')
    expect(window.location.hash).toBe('#/projects')
    expect(current.matched.some((item) => item.path === '/projects/:id')).toBe(false)
  })

  it('查询参数里不出现旧路径上的数字 ID', async () => {
    await router.replace('/projects/7?from=bookmark')
    await router.isReady()
    const current = router.currentRoute.value
    expect(current.path).toBe('/projects')
    expect(current.params).toEqual({})
    expect(current.query).toEqual({ from: 'bookmark' })
    expect(current.fullPath).toBe('/projects?from=bookmark')
  })

  it('从其他页面进入旧地址后，返回回到进入前的页面', async () => {
    await router.push('/projects/99')
    await router.isReady()
    expect(router.currentRoute.value.fullPath).toBe('/projects')
    expect(window.location.hash).toBe('#/projects')
    expect(history.state.current).toBe('/projects')
    expect(history.state.back).toBe('/reviews')

    const landed = new Promise<string>((resolve) => {
      const stop = router.afterEach((to) => {
        stop()
        resolve(to.fullPath)
      })
    })
    router.back()
    expect(await landed).toBe('/reviews')
  })
})
