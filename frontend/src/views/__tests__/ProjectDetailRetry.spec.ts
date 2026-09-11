// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'

/**
 * 重审的二次确认。
 *
 * 重审不是无副作用的操作：它真的再调一次大模型（花额度），并且把上一次的结果覆盖掉，
 * 误点之后没有任何撤销入口。原先两个入口（结果区的"重审失败单元"、记录列表的"重审"）
 * 都是点下去立刻发请求，这里把两道门都改成"先确认、再发请求"。
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
    retryReview: vi.fn().mockResolvedValue(undefined)
  }
})

vi.mock('@/api/project', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/project')>()
  return {
    ...actual,
    getBranches: vi.fn().mockResolvedValue(['master']),
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
import { getReview, listReviews, retryReview } from '@/api/review'

const options = {
  global: {
    stubs: {
      AccuracyBar: true,
      ReportPanel: true,
      ReviewConfigSnapshot: true,
      ReviewResult: true,
      CommitTable: true,
      ChangedFileTree: true,
      ReviewRecordViewer: true
    }
  }
}

function row() {
  return {
    id: '1001',
    projectId: '9',
    strategyId: '5',
    strategyName: '变更审查（diff）',
    branch: 'master',
    commitSha: 'abcdef1234567890',
    status: 3,
    progress: 100,
    createdAt: '2026-09-10 08:00:00'
  }
}

/** 与 ProjectDetailPolling.spec.ts 一致：用假定时器驱动等待，避免真实等待 */
const flush = () => vi.advanceTimersByTimeAsync(0)

/** 挂到「审查记录」页签并等表格渲染出来 */
async function mountOnRecords() {
  ;(listReviews as any).mockResolvedValue({ records: [row()], total: 1, current: 1, size: 20 })
  const wrapper = mount(ProjectDetail, options)
  await flush()
  await wrapper.findAllComponents({ name: 'ATabs' })[0].vm.$emit('update:activeKey', 'records')
  await wrapper.vm.$nextTick()
  await flush()
  return wrapper
}

function modalByTitle(wrapper: ReturnType<typeof mount>, title: string) {
  return wrapper.findAllComponents({ name: 'AModal' }).find((m) => m.props('title') === title)
}

function button(wrapper: ReturnType<typeof mount>, text: string) {
  return wrapper.findAll('button').find((b) => b.text().replace(/\s/g, '') === text)
}

describe('重审二次确认', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    vi.clearAllMocks()
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('记录列表点「重审」只弹确认框，不发请求', async () => {
    const wrapper = await mountOnRecords()

    await button(wrapper, '重审')!.trigger('click')
    await flush()

    const modal = modalByTitle(wrapper, '确认重审该次审查')
    expect(modal, '应弹出重审确认框').toBeTruthy()
    expect(modal!.props('open')).toBe(true)
    expect(retryReview).not.toHaveBeenCalled()
  })

  it('确认框里说明会消耗额度并覆盖结果', async () => {
    const wrapper = await mountOnRecords()

    await button(wrapper, '重审')!.trigger('click')
    await flush()

    expect(document.body.textContent).toContain('额度')
    expect(document.body.textContent).toContain('覆盖')
  })

  it('点确认后才真正重审这条记录', async () => {
    const wrapper = await mountOnRecords()

    await button(wrapper, '重审')!.trigger('click')
    await flush()
    await modalByTitle(wrapper, '确认重审该次审查')!.vm.$emit('ok')
    await flush()

    expect(retryReview).toHaveBeenCalledWith('1001')
  })

  it('结果区的「重审」走同一个确认框，未确认不发请求', async () => {
    const wrapper = await mountOnRecords()
    // 结果区的重审按钮只在"有当前审查"时出现，先用轮询把 review 灌进去
    ;(getReview as any).mockResolvedValue({ id: '1001', status: 3, progress: 100 })
    ;(wrapper.vm as any).startPoll('1001')
    await vi.advanceTimersByTimeAsync(2000)

    await wrapper.findComponent({ name: 'ReviewResult' }).vm.$emit('retry')
    await flush()

    expect(modalByTitle(wrapper, '确认重审失败单元'), '结果区重审应弹「确认重审失败单元」').toBeTruthy()
    expect(retryReview).not.toHaveBeenCalled()
  })
})
