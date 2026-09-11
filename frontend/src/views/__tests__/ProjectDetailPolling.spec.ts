// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'

/**
 * 进度轮询的失败韧性（A 批 E9）。
 *
 * 回归背景：原先**任何一次**请求失败就 `clearInterval` 静默退出 ——
 * 界面永远停在最后一次进度上，既不报错也没有恢复入口，只能刷新页面。
 * 现在：连续失败到上限才停，并给出「继续等待」。
 */
vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { id: '9' } }),
  useRouter: () => ({ push: vi.fn() })
}))

vi.mock('@/api/review', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/review')>()
  return {
    ...actual,
    getReview: vi.fn(),
    listReviews: vi.fn().mockResolvedValue({ records: [], total: 0, current: 1, size: 20 }),
    listMarks: vi.fn().mockResolvedValue([]),
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
import { getReview } from '@/api/review'

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
      ReviewRecordViewer: true,
      LoadErrorAlert: true
    }
  }
}

interface PollingVm {
  startPoll: (id: string) => void
  resumePoll: () => void
  stopPoll: () => void
  pollError: string
}

async function mountDetail() {
  const wrapper = mount(ProjectDetail, options)
  await flush()
  return wrapper
}

const flush = () => vi.advanceTimersByTimeAsync(0)

/** 推进一个轮询周期（间隔 2s） */
const tick = () => vi.advanceTimersByTimeAsync(2000)

describe('ProjectDetail 进度轮询韧性', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    vi.clearAllMocks()
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('单次失败不再永久停止轮询', async () => {
    ;(getReview as any).mockRejectedValueOnce(new Error('网络抖动'))
    const wrapper = await mountDetail()
    const vm = wrapper.vm as unknown as PollingVm

    vm.startPoll('r1')

    await tick()
    expect(getReview).toHaveBeenCalledTimes(1)
    expect(vm.pollError).toBe('')

    // 关键：失败过一次之后仍要继续拉，而不是静默退出
    ;(getReview as any).mockResolvedValue({ id: 'r1', status: 1, progress: 50 })
    await tick()

    expect(getReview).toHaveBeenCalledTimes(2)
    expect(vm.pollError).toBe('')

    vm.stopPoll()
  })

  it('连续失败到上限才停，并暴露错误供界面给出恢复入口', async () => {
    ;(getReview as any).mockRejectedValue(new Error('无法连接服务器，请检查网络或后端服务是否已启动'))
    const wrapper = await mountDetail()
    const vm = wrapper.vm as unknown as PollingVm

    vm.startPoll('r1')

    await tick()
    await tick()
    expect(getReview).toHaveBeenCalledTimes(2)
    expect(vm.pollError).toBe('') // 还没到上限，不该急着报错

    await tick()
    expect(getReview).toHaveBeenCalledTimes(3)
    expect(vm.pollError).toContain('无法连接服务器')

    // 已停止：再推进时间也不该继续请求
    await tick()
    await tick()
    expect(getReview).toHaveBeenCalledTimes(3)
  })

  it('「继续等待」恢复轮询并立即拉一次；成功后清掉错误', async () => {
    ;(getReview as any).mockRejectedValue(new Error('请求超时，请稍后重试'))
    const wrapper = await mountDetail()
    const vm = wrapper.vm as unknown as PollingVm

    vm.startPoll('r1')
    await tick()
    await tick()
    await tick()
    expect(vm.pollError).not.toBe('')

    ;(getReview as any).mockResolvedValue({ id: 'r1', status: 1, progress: 60 })
    vm.resumePoll()
    await flush()

    // 立即拉了一次，且错误被清空
    expect(getReview).toHaveBeenCalledTimes(4)
    expect(vm.pollError).toBe('')

    // 恢复后继续按周期轮询
    await tick()
    expect(getReview).toHaveBeenCalledTimes(5)

    vm.stopPoll()
  })

  it('审查进入终态后停止轮询', async () => {
    ;(getReview as any).mockResolvedValue({ id: 'r1', status: 2, progress: 100 })
    const wrapper = await mountDetail()
    const vm = wrapper.vm as unknown as PollingVm

    vm.startPoll('r1')
    await tick()
    expect(getReview).toHaveBeenCalledTimes(1)

    await tick()
    await tick()
    expect(getReview, '已成功就不该再轮询').toHaveBeenCalledTimes(1)
  })
})
