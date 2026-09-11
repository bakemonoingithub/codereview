// @vitest-environment happy-dom
import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import ChangedFileTree from '@/components/ChangedFileTree.vue'
import { namedAntStubs } from '@/testUtils/antStubs'
import type { ChangedFile } from '@/utils/changedFiles'

/**
 * 变更文件树的两处选择语义（C7 / C10）。
 *
 * 用真实 a-tree：只有真渲染才断言得到"树到底在不在、节点有没有出来"。
 */
const files: ChangedFile[] = [
  { path: 'src/a/A.java', status: 'modified' },
  { path: 'src/b/B.java', status: 'added' },
  { path: 'src/c/C.java', status: 'removed' }
]

const options = {
  global: {
    stubs: {
      ...namedAntStubs,
      'a-tree': false
    }
  }
}

function mountTree(props: Record<string, unknown> = {}) {
  return mount(ChangedFileTree, {
    props: { files, checked: [], ...props },
    ...options
  })
}

describe('C7 截断不再吃掉文件树', () => {
  it('truncated 时既显示警告、也照常渲染树与文件', async () => {
    const wrapper = mountTree({ truncated: true })
    await wrapper.vm.$nextTick()

    // a-alert 的 message 是 prop（stub 渲染成元素属性），不是插槽文本
    const hasWarning = wrapper
      .findAll('a-alert-stub')
      .some((a) => (a.attributes('message') || '').includes('列表可能不完整'))
    expect(hasWarning, '应显示截断警告').toBe(true)
    // 关键回归：原先 truncated 与 a-tree 互斥，警告一出树就没了 —— 用户无从勾选
    expect(wrapper.findComponent({ name: 'ATree' }).exists()).toBe(true)
    expect(wrapper.text()).toContain('A.java')
    expect(wrapper.text()).toContain('B.java')
  })

  it('未截断时不显示截断警告', async () => {
    const wrapper = mountTree({ truncated: false })
    await wrapper.vm.$nextTick()

    const hasWarning = wrapper
      .findAll('a-alert-stub')
      .some((a) => (a.attributes('message') || '').includes('列表可能不完整'))
    expect(hasWarning).toBe(false)
    expect(wrapper.findComponent({ name: 'ATree' }).exists()).toBe(true)
  })

  it('出错时只显示错误与重试，不渲染树', async () => {
    const wrapper = mountTree({ error: '变更文件加载失败' })
    await wrapper.vm.$nextTick()

    expect(wrapper.find('a-alert-stub').exists()).toBe(true)
    expect(wrapper.findComponent({ name: 'ATree' }).exists()).toBe(false)
  })
})

describe('C10 全选只作用于当前筛选结果', () => {
  it('按路径筛出 1 个后点全选，只选中那 1 个（而不是全部 3 个）', async () => {
    const wrapper = mountTree()

    const search = wrapper.find('a-input-stub input')
    await search.setValue('B.java')
    await wrapper.vm.$nextTick()

    const selectAll = wrapper.findAll('a-button-stub').find((b) => b.text() === '全选筛选结果')!
    expect(selectAll, '筛选生效后按钮应改叫「全选筛选结果」').toBeTruthy()
    await selectAll.trigger('click')

    expect(wrapper.emitted('update:checked')?.[0]).toEqual([['src/b/B.java']])
  })

  it('没有筛选时按钮就叫「全选」，点了选中全部可审文件', async () => {
    const wrapper = mountTree()

    const selectAll = wrapper.findAll('a-button-stub').find((b) => b.text() === '全选')!
    expect(selectAll).toBeTruthy()
    await selectAll.trigger('click')

    const emitted = wrapper.emitted('update:checked')?.[0]?.[0] as string[]
    expect(emitted.sort()).toEqual(['src/a/A.java', 'src/b/B.java', 'src/c/C.java'])
  })
})
