// @vitest-environment happy-dom
import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import CommitPickerModal from '@/components/CommitPickerModal.vue'
import { namedAntStubs } from '@/testUtils/antStubs'

const sha = (character: string) => character.repeat(40)
const commit = (commitSha: string, message: string, author: string, date = '2026-10-24T08:00:00Z') => ({
  sha: commitSha,
  message,
  author,
  date
})

function mountPicker(props: Record<string, unknown> = {}) {
  return mount(CommitPickerModal, {
    props: {
      open: true,
      branch: 'main',
      commits: [],
      selectedSha: '',
      hasMore: false,
      ...props
    } as any,
    global: {
      stubs: {
        ...namedAntStubs,
        'a-modal': { name: 'AModal', template: '<div class="modal-stub"><slot /></div>' }
      }
    }
  })
}

function button(wrapper: any, label: string) {
  return wrapper.findAll('a-button-stub').find((item: any) => item.text().trim() === label)!
}

describe('CommitPickerModal', () => {
  it('多选复选框遵守上限，清空草稿后取消可恢复或确定清空', async () => {
    const first = sha('a'); const second = sha('b'); const third = sha('c')
    const wrapper = mountPicker({ multiple: true, maxSelections: 2, selectedShas: [first],
      commits: [commit(first, '第一条', '甲'), commit(second, '第二条', '乙'), commit(third, '第三条', '丙')] })
    const rows = () => wrapper.findAll('.commit-row')
    expect((rows()[0].find('input').element as HTMLInputElement).checked).toBe(true)
    await rows()[1].find('input').setValue(true)
    expect(wrapper.text()).toContain('已选 2/2 条提交')
    expect(rows()[2].find('input').attributes('disabled')).toBeDefined()
    expect(rows()[0].find('input').attributes('disabled')).toBeUndefined()
    await button(wrapper, '清空选择').trigger('click')
    expect(wrapper.text()).toContain('已选 0/2 条提交')
    expect(button(wrapper, '清空选择').attributes('disabled')).toBeDefined()
    await button(wrapper, '取消').trigger('click')
    expect(wrapper.emitted('confirm-many')).toBeUndefined()
    await wrapper.setProps({ open: false }); await wrapper.setProps({ open: true })
    expect((rows()[0].find('input').element as HTMLInputElement).checked).toBe(true)
    await button(wrapper, '清空选择').trigger('click')
    await button(wrapper, '确定').trigger('click')
    expect(wrapper.emitted('confirm-many')).toEqual([[[]]])
    wrapper.unmount()
  })

  it('单选和定位结果的复选框可选择及取消，清空后可确认', async () => {
    const first = sha('a'); const located = sha('b')
    const wrapper = mountPicker({ selectedSha: first,
      commits: [commit(first, '第一条', '甲')], locatedCommit: commit(located, '定位提交', '乙') })
    await wrapper.find('.located-row input').setValue(true)
    expect((wrapper.find('.located-row input').element as HTMLInputElement).checked).toBe(true)
    expect((wrapper.find('.commit-list input').element as HTMLInputElement).checked).toBe(false)
    await wrapper.find('.located-row input').setValue(false)
    expect(wrapper.find('.selected-commits').exists()).toBe(false)
    await button(wrapper, '确定').trigger('click')
    expect(wrapper.emitted('confirm')).toEqual([['']])
    wrapper.unmount()
  })

  it('只在已加载提交中按 SHA、说明和作者筛选', async () => {
    const alice = commit(sha('a'), 'Fix Login\n详情', 'Alice', '2026-10-23T08:00:00Z')
    const bob = commit(sha('b'), '补充文档', 'Bob', '2026-10-24T08:00:00Z')
    const wrapper = mountPicker({ commits: [alice, bob], hasMore: true })
    const input = wrapper.find('input')

    expect(wrapper.text()).toContain('关键词仅筛选已加载提交')
    expect(wrapper.text()).toContain('完整 40 位 SHA 可直接定位')
    expect(wrapper.findAll('.commit-row')).toHaveLength(2)

    await input.setValue('ALICE')
    expect(wrapper.findAll('.commit-row')).toHaveLength(1)
    expect(wrapper.text()).toContain('Fix Login')

    await input.setValue('bbbbbb')
    expect(wrapper.findAll('.commit-row')).toHaveLength(1)
    expect(wrapper.text()).toContain('补充文档')

    await input.setValue('登录')
    expect(wrapper.findAll('.commit-row')).toHaveLength(0)
    await input.setValue('2026-10-24')
    expect(wrapper.findAll('.commit-row')).toHaveLength(0)
    wrapper.unmount()
  })

  it('取消不提交草稿选择，再次打开时恢复已选提交', async () => {
    const selected = sha('a'); const replacement = sha('b')
    const wrapper = mountPicker({
      commits: [commit(selected, '当前提交', '张三'), commit(replacement, '新提交', '李四')],
      selectedSha: selected
    })

    const replacementRow = wrapper.findAll('.commit-row').find((row) => row.text().includes('新提交'))!
    await replacementRow.find('.commit-choice').trigger('click')
    await button(wrapper, '取消').trigger('click')
    expect(wrapper.emitted('update:open')).toEqual([[false]])
    expect(wrapper.emitted('confirm')).toBeUndefined()

    await wrapper.setProps({ open: false })
    await wrapper.setProps({ open: true })
    expect(wrapper.find('.commit-row.selected').text()).toContain('当前提交')
    await button(wrapper, '确定').trigger('click')
    expect(wrapper.emitted('confirm')).toEqual([[selected]])
    wrapper.unmount()
  })

  it('只有确认时才提交新选中的 SHA，双击也可直接确认', async () => {
    const selected = sha('a'); const replacement = sha('b')
    const wrapper = mountPicker({
      commits: [commit(selected, '当前提交', '张三'), commit(replacement, '新提交', '李四')],
      selectedSha: selected
    })
    const replacementRow = wrapper.findAll('.commit-row').find((row) => row.text().includes('新提交'))!

    await replacementRow.find('.commit-choice').trigger('click')
    expect(wrapper.emitted('confirm')).toBeUndefined()
    await button(wrapper, '确定').trigger('click')
    await replacementRow.find('.commit-choice').trigger('dblclick')
    expect(wrapper.emitted('confirm')).toEqual([[replacement], [replacement]])
    wrapper.unmount()
  })

  it('触底和按钮都能加载更早提交，错误时停止续读', async () => {
    const wrapper = mountPicker({
      commits: [commit(sha('a'), '最新提交', '张三')],
      hasMore: true
    })
    const list = wrapper.find('.commit-list')
    Object.defineProperties(list.element, {
      scrollTop: { configurable: true, value: 700 },
      clientHeight: { configurable: true, value: 300 },
      scrollHeight: { configurable: true, value: 1000 }
    })

    await list.trigger('scroll')
    await button(wrapper, '加载更多').trigger('click')
    expect(wrapper.emitted('load-more')).toHaveLength(2)

    await wrapper.setProps({ error: '加载失败' })
    expect(button(wrapper, '加载更多').attributes('disabled')).toBeDefined()
    await list.trigger('scroll')
    expect(wrapper.emitted('load-more')).toHaveLength(2)

    await wrapper.setProps({ error: '', hasMore: false })
    expect(wrapper.text()).toContain('已加载完该分支提交历史')
    expect(button(wrapper, '加载更多')).toBeUndefined()
    wrapper.unmount()
  })

  it('提供刷新和失败重试入口', async () => {
    const wrapper = mountPicker({ error: '暂时失败' })
    await button(wrapper, '刷新').trigger('click')
    await button(wrapper, '重试').trigger('click')
    expect(wrapper.emitted('refresh')).toEqual([[]])
    expect(wrapper.emitted('retry')).toEqual([[]])
    expect(button(wrapper, '确定').attributes('disabled')).toBeDefined()
    wrapper.unmount()
  })

  it('完整 SHA 才可直接定位，定位结果与历史列表分开展示', async () => {
    const wrapper = mountPicker({ commits: [commit(sha('a'), '最近提交', '张三')], hasMore: true })
    await wrapper.find('input').setValue('bbbbbbb')
    expect(button(wrapper, '按 SHA 定位').attributes('disabled')).toBeDefined()
    await wrapper.find('input').setValue(`  ${sha('B')}  `)
    await button(wrapper, '按 SHA 定位').trigger('click')
    expect(wrapper.emitted('lookup')).toEqual([[sha('b')]])

    await wrapper.setProps({ locatedCommit: commit(sha('b'), '很早的提交', '李四') })
    expect(wrapper.text()).toContain('未核对是否属于所选分支')
    expect(wrapper.text()).toContain('已加载 1 条')
    await wrapper.find('.located-row .commit-choice').trigger('click')
    await button(wrapper, '确定').trigger('click')
    expect(wrapper.emitted('confirm')).toEqual([[sha('b')]])
    await wrapper.find('input').setValue('新查询')
    expect(wrapper.emitted('clear-lookup')!.length).toBeGreaterThan(0)
    wrapper.unmount()
  })

  it('复制的旧提交即使不在第一页也保留摘要与选中状态', () => {
    const selected = commit(sha('b'), '复制的旧提交', '李四')
    const wrapper = mountPicker({
      commits: [commit(sha('a'), '最新提交', '张三')],
      selectedSha: selected.sha,
      selectedCommit: selected
    })
    expect(wrapper.find('.located-row.selected').text()).toContain('复制的旧提交')
    expect(wrapper.text()).toContain('当前已选（不在已加载的分支历史中）')
    wrapper.unmount()
  })

  it('上千条历史只渲染可见部分，筛选仍覆盖全部已加载提交', async () => {
    const commits = Array.from({ length: 1000 }, (_, index) =>
      commit(index.toString(16).padStart(40, '0'), `历史提交 ${index}`, '作者'))
    const wrapper = mountPicker({ commits, hasMore: false })
    expect(wrapper.findAll('.commit-row').length).toBeLessThan(20)
    await wrapper.find('input').setValue('历史提交 999')
    expect(wrapper.findAll('.commit-row')).toHaveLength(1)
    expect(wrapper.find('.commit-row').text()).toContain('历史提交 999')
    wrapper.unmount()
  })
})
