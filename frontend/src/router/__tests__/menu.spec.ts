// @vitest-environment happy-dom
// 需要 DOM：本测试导入 @/router，该模块会 createWebHashHistory()（读 location）
import { describe, expect, it } from 'vitest'
import { activeMenuKey, menuItems } from '@/router'

/**
 * 导航与"我在哪"（C2）。
 *
 * 回归背景：菜单项原先在 BasicLayout 里硬编码，`meta.title`/`hideMenu` 无人消费（死配置），
 * 且高亮用 `[route.path]` 直接比对 —— 进项目详情页后侧边栏**没有任何高亮**。
 */
describe('侧边栏菜单', () => {
  it('由路由表生成，且排除 hideMenu 的项（项目详情不进菜单）', () => {
    const paths = menuItems.map((m) => m.path)

    expect(paths).toEqual(['/projects', '/models', '/strategies', '/prompts'])
    expect(paths).not.toContain('/projects/:id')
  })

  it('菜单标题取自路由 meta.title（不再是另写一份硬编码）', () => {
    expect(menuItems.map((m) => m.title)).toEqual(['项目', '模型', '策略', '提示词'])
  })

  /**
   * 图标与菜单同源（放在 `meta.icon` 里）。
   *
   * 这条断言的意义是**拦住"以后加了个路由、忘了配图标"**：侧栏折叠时文字会被 antd 隐藏，
   * 没有图标的菜单项在收起状态下就是一片空白 —— 静默且难查，所以让它变成一个失败的测试。
   */
  it('每个菜单项都配了图标（否则折叠后该项是空白）', () => {
    expect(menuItems.every((m) => !!m.icon)).toBe(true)
  })
})

describe('菜单高亮归属', () => {
  it('列表页精确匹配', () => {
    expect(activeMenuKey('/projects')).toBe('/projects')
    expect(activeMenuKey('/strategies')).toBe('/strategies')
  })

  it('详情页归到它的上一级 —— 这是原先缺失的行为', () => {
    expect(activeMenuKey('/projects/123')).toBe('/projects')
    expect(activeMenuKey('/projects/abc-def')).toBe('/projects')
  })

  it('认不出的路径不高亮任何项', () => {
    expect(activeMenuKey('/unknown')).toBe('')
    expect(activeMenuKey('/')).toBe('')
  })

  it('不会把前缀相近的路径误判（/projectsX 不属于 /projects）', () => {
    expect(activeMenuKey('/projectsX')).toBe('')
  })
})
