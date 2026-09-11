// @vitest-environment happy-dom
import { describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'

/**
 * 「审查记录」分页器**真实渲染**的回归测试（表格与分页器都不 stub）。
 *
 * 背景：用户报"18 条数据、每页 10 条时看不到下一页与每页条数选项"。
 * 后端已实测返回 {"records":[...],"total":18,"size":10,"current":1,"pages":2}，
 * 所以这里回答的是前端侧：拿到这样的响应，页码到底渲不渲染得出来。
 *
 * 注意：antd Tabs 懒渲染，默认停在「代码审查」，必须先把 activeKey 切到 records，
 * 否则表格压根不在 DOM 里 —— 断言会以"找不到分页器"的形式假阳性失败。
 */
vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { id: '9' } }),
  useRouter: () => ({ push: vi.fn() })
}))

vi.mock('@/api/review', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/review')>()
  return {
    ...actual,
    listReviews: vi.fn(),
    listMarks: vi.fn().mockResolvedValue([]),
    getReview: vi.fn(),
    retryReview: vi.fn()
  }
})

vi.mock('@/api/project', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/project')>()
  return {
    ...actual,
    getBranches: vi.fn().mockResolvedValue([]),
    getTree: vi.fn().mockResolvedValue([]),
    listCommitPage: vi.fn().mockResolvedValue({ commits: [], hasMore: false }),
    getCommitDetail: vi.fn(),
    getAccuracy: vi.fn().mockResolvedValue([]),
    getProject: vi.fn().mockResolvedValue({ id: '9', name: '演示项目', giteaUrl: 'http://gitea/team/repo' })
  }
})

vi.mock('@/api/strategy', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/strategy')>()
  return { ...actual, listStrategies: vi.fn().mockResolvedValue({ records: [] }) }
})

import ProjectDetail from '@/views/ProjectDetail.vue'
import { listReviews } from '@/api/review'

function row(index: number) {
  return {
    id: String(1000 + index),
    projectId: '9',
    strategyId: '5',
    strategyName: '变更审查（diff）',
    branch: 'master',
    commitSha: 'abcdef1234567890',
    status: 2,
    progress: 100,
    createdAt: '2026-09-10 08:00:00'
  }
}

const options = {
  global: {
    stubs: {
      AccuracyBar: true,
      ReportPanel: true,
      ReviewConfigSnapshot: true,
      ReviewResult: true,
      SplitPane: true,
      CommitTable: true,
      ChangedFileTree: true,
      ReviewRecordViewer: true
    }
  }
}

function mockPage(pageNum: number, size: number, total: number) {
  const start = (pageNum - 1) * size
  const count = Math.max(0, Math.min(size, total - start))
  ;(listReviews as any).mockResolvedValue({
    records: Array.from({ length: count }, (_, i) => row(start + i)),
    total,
    current: pageNum,
    size
  })
}

/** 挂载后切到「审查记录」页签，让表格真正渲染出来 */
async function mountOnRecordsTab(total: number, firstSize = 20) {
  mockPage(1, firstSize, total)
  const wrapper = mount(ProjectDetail, options)
  await new Promise((resolve) => setTimeout(resolve, 0))
  const tabs = wrapper.findAllComponents({ name: 'ATabs' })[0]
  await tabs.vm.$emit('update:activeKey', 'records')
  await wrapper.vm.$nextTick()
  await new Promise((resolve) => setTimeout(resolve, 0))
  await wrapper.vm.$nextTick()
  return wrapper
}

/** 取出审查记录表格的分页配置 */
function paginationOf(wrapper: Awaited<ReturnType<typeof mountOnRecordsTab>>) {
  const table = wrapper
    .findAllComponents({ name: 'ATable' })
    .find((t) => (t.props('pagination') as any)?.showTotal)
  return table!.props('pagination') as any
}

describe('审查记录分页器真实渲染（数据 18 条）', () => {
  it('首次请求按每页 20 条带 pageNum/pageSize', async () => {
    await mountOnRecordsTab(18)
    expect(listReviews).toHaveBeenCalledWith('9', { pageNum: 1, pageSize: 20 })
  })

  it('分页配置带上真实 total 与每页条数选项', async () => {
    const wrapper = await mountOnRecordsTab(18)
    const pagination = paginationOf(wrapper)
    expect(pagination.total).toBe(18)
    expect(pagination.pageSize).toBe(20)
    expect(pagination.showSizeChanger).toBe(true)
    expect(pagination.pageSizeOptions).toEqual(['10', '20', '50'])
  })

  it('把每页条数改成 10 后，重新按 pageSize=10 拉数据', async () => {
    const wrapper = await mountOnRecordsTab(18)
    mockPage(1, 10, 18)
    await paginationOf(wrapper).onChange(1, 10)
    await wrapper.vm.$nextTick()
    expect(listReviews).toHaveBeenLastCalledWith('9', { pageNum: 1, pageSize: 10 })
    expect(paginationOf(wrapper).pageSize).toBe(10)
  })

  it('走真实下拉框改每页条数（不绕过 antd 分页器内部）', async () => {
    const wrapper = await mountOnRecordsTab(18)
    const sizeSelect = wrapper
      .findAllComponents({ name: 'ASelect' })
      .find((s) => s.attributes('class')?.includes('ant-pagination-options-size-changer'))
    expect(sizeSelect, '分页器上应存在每页条数选择器').toBeTruthy()

    mockPage(1, 10, 18)
    await sizeSelect!.vm.$emit('change', 10)
    await wrapper.vm.$nextTick()
    await new Promise((resolve) => setTimeout(resolve, 0))

    expect(listReviews).toHaveBeenLastCalledWith('9', { pageNum: 1, pageSize: 10 })
  })

  it('每页 10 条、18 条数据时翻到第 2 页只取回 8 条', async () => {
    const wrapper = await mountOnRecordsTab(18)
    mockPage(1, 10, 18)
    await paginationOf(wrapper).onChange(1, 10)
    await wrapper.vm.$nextTick()

    mockPage(2, 10, 18)
    await paginationOf(wrapper).onChange(2, 10)
    await wrapper.vm.$nextTick()

    expect(listReviews).toHaveBeenLastCalledWith('9', { pageNum: 2, pageSize: 10 })
    expect(paginationOf(wrapper).total).toBe(18)
    expect(paginationOf(wrapper).current).toBe(2)
  })

  it('后端 total 缺失时按当前页条数兜底，分页器不会整个消失', async () => {
    ;(listReviews as any).mockResolvedValue({ records: [row(0), row(1)], current: 1, size: 20 })
    const wrapper = mount(ProjectDetail, options)
    await new Promise((resolve) => setTimeout(resolve, 0))
    const tabs = wrapper.findAllComponents({ name: 'ATabs' })[0]
    await tabs.vm.$emit('update:activeKey', 'records')
    await wrapper.vm.$nextTick()

    expect(paginationOf(wrapper).total).toBe(2)
  })

  it('18 条数据、每页 20 条时：单页也照常渲染分页条与每页条数选择器', async () => {
    const wrapper = await mountOnRecordsTab(18)

    expect(wrapper.findAll('.ant-table-tbody tr.ant-table-row')).toHaveLength(18)
    // 单页时 antd 默认也会显示分页条 —— 用户要在 18 条的情况下切到"10 条/页"就靠它
    expect(wrapper.findAll('.ant-pagination').length).toBeGreaterThan(0)
    expect(wrapper.find('.ant-pagination-options').exists()).toBe(true)
  })
})
