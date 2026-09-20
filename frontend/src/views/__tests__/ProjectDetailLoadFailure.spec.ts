// @vitest-environment happy-dom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { nextTick } from 'vue'
import { mount } from '@vue/test-utils'

/**
 * 标记与准确率加载失败**不再静默清空**（backlog ⑥）。
 *
 * 回归背景：
 * - `loadMarks` 原先 `catch { marks.value = [] }` —— 已点的"误报/已采纳"整批消失，看起来像从没标过；
 * - `loadAccuracy` 同样清空，而 `AccuracyBar` 把空数组渲染成"暂无审查结果，无法统计准确率"，
 *   于是**接口失败被误报成"本来就没有结果"**（指标 5 的口径会因此失真）。
 *
 * 修法：失败时保留上一次的数据、显式给出错误提示与重试入口。
 */
vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { id: '9' } }),
  useRouter: () => ({ push: vi.fn() })
}))

vi.mock('@/api/review', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/review')>()
  return {
    ...actual,
    listReviews: vi.fn().mockResolvedValue({ records: [], total: 0, current: 1, size: 20 }),
    listMarks: vi.fn(),
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
    getAccuracy: vi.fn(),
    getProject: vi.fn().mockResolvedValue({ id: '9', name: '演示项目', giteaUrl: 'http://gitea/team/repo' })
  }
})

vi.mock('@/api/strategy', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/strategy')>()
  return { ...actual, listStrategies: vi.fn().mockResolvedValue({ records: [] }) }
})

import ProjectDetail from '@/views/ProjectDetail.vue'
import { listMarks } from '@/api/review'
import { getAccuracy } from '@/api/project'
import { attrsStub } from '@/testUtils/antStubs'

const options = {
  global: {
    stubs: {
      // antd 4.x 的 a-alert 不渲染 `#action` 插槽里的内容，"重试"就点不到；
      // 换成保留插槽的 stub（与 LoadErrorAlert.spec 同一套路）
      'a-alert': attrsStub('a-alert-stub'),
      'a-button': attrsStub('a-button-stub'),
      AccuracyBar: true,
      ReportPanel: true,
      ReviewConfigSnapshot: true,
      ReviewResult: true,
      SplitPane: true,
      CommitTable: true,
      ChangedFileTree: true,
      FileViewerModal: true,
      ReviewRecordViewer: true
    }
  }
}

const flush = () => new Promise((resolve) => setTimeout(resolve, 0))

/**
 * 挂载后切到「审查记录」页签。
 *
 * antd Tabs 懒渲染：不切过去，准确率那块的 DOM 根本不存在（断言会以"找不到提示"假失败）。
 */
async function mountOnRecordsTab() {
  const wrapper = mount(ProjectDetail, options)
  await flush()
  const tabs = wrapper.findAllComponents({ name: 'ATabs' })[0]
  await tabs.vm.$emit('update:activeKey', 'records')
  await nextTick()
  await flush()
  await nextTick()
  return wrapper
}

function retryButton(wrapper: any) {
  return wrapper.findAll('a-button-stub').find((b: any) => b.text() === '重试')
}

function alertMessage(wrapper: any) {
  const alert = wrapper.find('a-alert-stub')
  return alert.exists() ? String(alert.attributes('message') ?? '') : ''
}

beforeEach(() => {
  vi.clearAllMocks()
})

describe('准确率加载失败', () => {
  it('失败时给出提示，而不是把接口失败显示成"暂无审查结果"', async () => {
    ;(getAccuracy as any).mockRejectedValue(new Error('准确率接口 500'))

    const wrapper = await mountOnRecordsTab()

    expect(alertMessage(wrapper), '失败必须在界面上说出来').toContain('准确率接口 500')
    expect(retryButton(wrapper), '失败后必须给重试入口').toBeTruthy()
  })

  it('点重试会重新拉取，成功后错误提示消失', async () => {
    ;(getAccuracy as any).mockRejectedValueOnce(new Error('准确率接口 500'))
    const wrapper = await mountOnRecordsTab()

    ;(getAccuracy as any).mockResolvedValueOnce([])
    await retryButton(wrapper)!.trigger('click')
    await flush()
    await nextTick()

    expect(getAccuracy).toHaveBeenCalledTimes(2)
    expect(alertMessage(wrapper)).not.toContain('准确率接口 500')
  })
})

describe('标记加载失败', () => {
  it('失败时保留上一次的标记，并给出错误提示', async () => {
    ;(getAccuracy as any).mockResolvedValue([])
    const wrapper = mount(ProjectDetail, options)
    await flush()
    await nextTick()
    const vm = wrapper.vm as any

    ;(listMarks as any).mockResolvedValueOnce([
      { id: 'm1', unitPath: 'A.java', issueIndex: 0, markValue: 2 }
    ])
    await vm.loadMarks('1001')
    expect(vm.marks.length).toBe(1)

    ;(listMarks as any).mockRejectedValueOnce(new Error('标记接口 500'))
    await vm.loadMarks('1001')

    expect(vm.marks.length, '失败不能把已标记的清成空').toBe(1)
    expect(String(vm.marksError)).toContain('标记接口 500')
  })

  it('成功后清掉上一次的错误', async () => {
    ;(getAccuracy as any).mockResolvedValue([])
    const wrapper = mount(ProjectDetail, options)
    await flush()
    await nextTick()
    const vm = wrapper.vm as any

    ;(listMarks as any).mockRejectedValueOnce(new Error('标记接口 500'))
    await vm.loadMarks('1001')
    expect(String(vm.marksError)).toContain('标记接口 500')

    ;(listMarks as any).mockResolvedValueOnce([])
    await vm.loadMarks('1001')
    expect(vm.marksError).toBe('')
  })
})
