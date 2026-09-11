// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import ReportPanel from '@/components/ReportPanel.vue'
import { namedAntStubs } from '@/testUtils/antStubs'

/**
 * 报告生成后的状态刷新（C5）。
 *
 * 回归背景：生成完只弹一句"已提交生成"，此后**不再刷新** ——
 * ReportPanel 一直在 DOM 里（antd Tabs 不懒渲染、也不重挂载），切页签也不会重拉，
 * 现场看到的是状态永远停在"排队"，只能手动刷页。
 */
vi.mock('@/api/review', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/review')>()
  return {
    ...actual,
    listReviews: vi.fn().mockResolvedValue({ records: [], total: 0, current: 1, size: 20 }),
    getReview: vi.fn(),
    listMarks: vi.fn().mockResolvedValue([])
  }
})
vi.mock('@/api/model', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/model')>()
  return { ...actual, listModels: vi.fn().mockResolvedValue({ records: [] }) }
})
vi.mock('@/api/prompt', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/prompt')>()
  return { ...actual, listPrompts: vi.fn().mockResolvedValue({ records: [] }) }
})
vi.mock('@/api/strategy', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/strategy')>()
  return { ...actual, listStrategies: vi.fn().mockResolvedValue({ records: [] }) }
})
vi.mock('@/api/report', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/report')>()
  return {
    ...actual,
    listReports: vi.fn(),
    generateReport: vi.fn(),
    getReport: vi.fn()
  }
})

import { listReports } from '@/api/report'

interface PollingVm {
  startReportPoll: () => void
  stopReportPoll: () => void
}

const options = {
  global: {
    stubs: {
      ...namedAntStubs,
      'a-table': false,
      'a-table-column': false,
      'a-modal': false,
      'a-row': false,
      'a-col': false,
      'a-card': false,
      'a-form': false,
      'a-form-item': false,
      ReviewRecordViewer: true
    }
  }
}

const tick = () => vi.advanceTimersByTimeAsync(5000)
const flush = () => vi.advanceTimersByTimeAsync(0)

function reportRow(status: number, id = '1') {
  return { id, projectId: '9', name: '报告', status, progress: 0, createdAt: '2026-09-10 08:00:00' }
}

async function mountPanel() {
  const wrapper = mount(ReportPanel, { props: { projectId: '9' }, ...options })
  await flush()
  return wrapper
}

describe('ReportPanel 报告状态轮询', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    vi.clearAllMocks()
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('存在"排队/生成中"的行时按周期刷新', async () => {
    ;(listReports as any).mockResolvedValue({ records: [reportRow(0)] })
    const wrapper = await mountPanel()
    const vm = wrapper.vm as unknown as PollingVm
    const afterMount = (listReports as any).mock.calls.length

    vm.startReportPoll()
    await tick()

    expect((listReports as any).mock.calls.length).toBe(afterMount + 1)
    vm.stopReportPoll()
  })

  it('全部完成后自动停止，不再空刷', async () => {
    ;(listReports as any).mockResolvedValue({ records: [reportRow(0)] })
    const wrapper = await mountPanel()
    const vm = wrapper.vm as unknown as PollingVm

    vm.startReportPoll()
    await tick()
    const afterFirstTick = (listReports as any).mock.calls.length

    // 下一轮返回"已完成" → 应停止
    ;(listReports as any).mockResolvedValue({ records: [reportRow(2)] })
    await tick()
    const afterFinished = (listReports as any).mock.calls.length
    await tick()
    await tick()

    expect((listReports as any).mock.calls.length).toBe(afterFinished)
    expect(afterFinished).toBeGreaterThan(afterFirstTick)
  })

  it('没有未完成报告时不启动轮询', async () => {
    ;(listReports as any).mockResolvedValue({ records: [reportRow(2)] })
    const wrapper = await mountPanel()
    const vm = wrapper.vm as unknown as PollingVm
    const afterMount = (listReports as any).mock.calls.length

    vm.startReportPoll()
    await tick()
    await tick()

    expect((listReports as any).mock.calls.length).toBe(afterMount)
  })

  it('轮询期间失败不打断（下一周期继续尝试）', async () => {
    ;(listReports as any).mockResolvedValue({ records: [reportRow(1)] })
    const wrapper = await mountPanel()
    const vm = wrapper.vm as unknown as PollingVm
    vm.startReportPoll()

    ;(listReports as any).mockRejectedValueOnce(new Error('请求超时，请稍后重试'))
    await tick()
    const afterFailure = (listReports as any).mock.calls.length

    ;(listReports as any).mockResolvedValue({ records: [reportRow(1)] })
    await tick()

    expect((listReports as any).mock.calls.length).toBeGreaterThan(afterFailure)
    vm.stopReportPoll()
  })
})
