// @vitest-environment happy-dom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import contract from '../../../../backend/src/test/resources/contracts/srf-result-feedback.json'
import SpecialistReviewResultTab from '@/components/SpecialistReviewResultTab.vue'
import TaskAccuracySummary from '@/components/TaskAccuracySummary.vue'
import ReportSourceModal from '@/components/ReportSourceModal.vue'
import FeedbackEditor from '@/components/FeedbackEditor.vue'
import { discardFeedbackDrafts, hasDirtyFeedbackDrafts } from '@/components/feedbackDrafts'
import { feedbackText } from '@/utils/reportCandidates'
import { namedAntStubs, silentStub } from '@/testUtils/antStubs'
import type { FeedbackView, ReportCandidate, ResultView, TaskStatistics } from '@/api/v2'

// 本检查只验证冻结数据；页面跳转已由来源视图的专项测试覆盖。
vi.mock('vue-router', async importOriginal => ({
  ...(await importOriginal<typeof import('vue-router')>()),
  useRouter: () => ({ resolve: () => ({ href: '/reviews/contract' }) })
}))
vi.mock('@/api/v2', () => ({ feedbackApi: { get: vi.fn(), save: vi.fn() }, reportsApi: { source: vi.fn() } }))
import { feedbackApi, reportsApi } from '@/api/v2'

const options = { global: { stubs: { ...namedAntStubs, 'a-modal': silentStub('modal'), CodeReferenceModal: true } } }
const samples = contract.cases.map(sample => ({ ...sample,
  resultView: sample.resultView as unknown as ResultView,
  statistics: sample.statistics as TaskStatistics,
  feedbacks: sample.feedbacks as FeedbackView[]
}))
const saved = new Map<string, FeedbackView>()
const key = (kind: string, id: string) => `${kind}:${id}`
const button = (wrapper: any, label: string) => wrapper.findAll('a-button-stub').find((item: any) => item.text() === label)!
const input = (wrapper: any) => wrapper.find('a-textarea-stub input')
const clone = <T,>(value: T): T => JSON.parse(JSON.stringify(value))

beforeEach(() => {
  vi.resetAllMocks(); saved.clear()
  for (const sample of samples) discardFeedbackDrafts(sample.taskId)
  vi.mocked(feedbackApi.get).mockImplementation(async (kind, id) => clone(saved.get(key(kind, id)) || {
    subjectKind: kind, subjectId: id, assessment: null, comment: null, revision: 0, updatedAt: null
  }))
  vi.mocked(feedbackApi.save).mockImplementation(async (kind, id, request) => {
    const current = saved.get(key(kind, id))
    if ((current?.revision || 0) !== request.expectedRevision) throw new Error('unexpected test revision')
    const row: FeedbackView = { subjectKind: kind, subjectId: id, assessment: request.assessment, comment: request.comment,
      revision: request.expectedRevision + 1, updatedAt: '2026-10-27T10:05:00' }
    saved.set(key(kind, id), row)
    return clone(row)
  })
})

describe('SRF 前后端共用契约样本', () => {
  it.each(samples)('$kind 的反馈主体、重进读取、统计和冻结来源使用同一关联', async sample => {
    let wrapper = mount(SpecialistReviewResultTab, { props: { taskId: sample.taskId, result: clone(sample.resultView) }, ...options })
    await flushPromises()
    const editors = wrapper.findAllComponents(FeedbackEditor)
    expect(editors).toHaveLength(4)
    expect(editors.map(editor => editor.props('subjectId'))).toEqual(sample.feedbacks.map(row => row.subjectId))
    for (let index = 0; index < editors.length; index++) {
      const editor = editors[index]
      const expected = sample.feedbacks[index]
      const label = ({ ACCURATE: '准确', INACCURATE: '不准确', UNKNOWN: '无法判断' } as Record<string, string>)[expected.assessment || '']
      if (label) await button(editor, label).trigger('click')
      await input(editor).setValue(expected.comment)
      expect(editor.find('a-textarea-stub').exists()).toBe(true)
      await button(editor, '保存反馈').trigger('click'); await flushPromises()
      expect(feedbackApi.save).toHaveBeenNthCalledWith(index + 1, sample.kind, expected.subjectId,
        { assessment: expected.assessment, comment: expected.comment, expectedRevision: 0 })
    }
    expect(hasDirtyFeedbackDrafts(sample.taskId)).toBe(false)
    wrapper.unmount()
    wrapper = mount(SpecialistReviewResultTab, { props: { taskId: sample.taskId, result: clone(sample.resultView) }, ...options })
    await flushPromises()
    expect(wrapper.findAllComponents(FeedbackEditor).map(editor => (input(editor).element as HTMLInputElement).value))
      .toEqual(sample.feedbacks.map(row => row.comment))
    const code = wrapper.find('.reference')
    expect(code.text()).toContain(sample.material.path)
    await code.trigger('click')
    expect(wrapper.emitted('open-code')?.[0]?.[0]).toMatchObject({ path: sample.material.path, sha: sample.material.sha, side: 'AFTER', startLine: 3, endLine: 5 })
    if (sample.kind === 'PATTERN') expect(wrapper.text()).toContain(sample.resultView.body.patterns![0].explanation)
    wrapper.unmount()

    const summary = mount(TaskAccuracySummary, { props: { specialistType: sample.statistics.specialistType, stats: sample.statistics } })
    expect(summary.text()).toContain('50%')
    expect(summary.text()).toContain('准确 1 · 不准确 1 · 无法判断 1 · 未反馈 1')
    expect(summary.text()).not.toContain('100%'); summary.unmount()
    if (sample.kind !== 'ISSUE') {
      const counts = sample.kind === 'PATTERN' ? sample.statistics.patterns : sample.statistics.couplings
      expect(feedbackText({ itemFeedback: counts, feedback: { assessment: 'ACCURATE', comment: 'legacy' } } as ReportCandidate))
        .toBe('准确 1 · 不准确 1 · 无法判断 1 · 未反馈 1')
    }

    // 同一份后端契约固定的反馈，按发布序号进入来源；不读取实时反馈。
    const subjects = sample.kind === 'ISSUE' ? sample.resultView.issues : sample.resultView.items || []
    const snapshot = { snapshotVersion: 2, resultId: sample.resultView.id, taskId: sample.taskId, kind: sample.kind,
      targetId: sample.targetId, completeness: 'COMPLETE', schemaVersion: '1', project: { id: sample.projectId },
      result: { body: sample.resultView.body, provenance: sample.resultView.provenance },
      issues: sample.kind === 'ISSUE' ? subjects.map((subject, index) => ({ ...subject, feedback: sample.feedbacks[index] })) : [],
      items: sample.kind === 'ISSUE' ? [] : subjects.map((subject, index) => ({ ...subject, feedback: sample.feedbacks[index] })),
      resultFeedback: { assessment: 'ACCURATE', comment: 'legacy whole result' } }
    vi.mocked(reportsApi.source).mockResolvedValue({ ordinal: 1, resultId: sample.resultView.id, snapshot } as any)
    saved.clear(); const readsBeforeSource = vi.mocked(feedbackApi.get).mock.calls.length
    const source = mount(ReportSourceModal, { props: { open: true, reportId: 'frozen-report', ordinal: 1 }, ...options })
    await flushPromises()
    expect(source.find('.feedback-summary').text()).toContain('准确 1 · 不准确 1 · 无法判断 1 · 未反馈 1')
    for (const expected of sample.feedbacks) expect(source.text()).toContain(expected.comment!)
    expect(source.text()).not.toContain('legacy whole result')
    expect(vi.mocked(feedbackApi.get).mock.calls.length).toBe(readsBeforeSource)
    source.unmount()
  })

  it.each(samples)('$kind 的同名条目草稿在换文件、专项和任务后仍各自保留', async sample => {
    const wrapper = mount(SpecialistReviewResultTab, { props: { taskId: sample.taskId, result: clone(sample.resultView) }, ...options })
    await flushPromises()
    const first = wrapper.findAllComponents(FeedbackEditor)[0]
    await input(first).setValue('原条目的草稿')
    const other = samples.find(candidate => candidate.kind !== sample.kind)!
    await wrapper.setProps({ taskId: other.taskId, result: clone(other.resultView) }); await flushPromises()
    expect(wrapper.findAllComponents(FeedbackEditor).every(editor => (input(editor).element as HTMLInputElement).value === '')).toBe(true)
    await input(wrapper.findAllComponents(FeedbackEditor)[1]).setValue('另一任务的第二条草稿')
    await wrapper.setProps({ taskId: sample.taskId, result: clone(sample.resultView) }); await flushPromises()
    const restored = wrapper.findAllComponents(FeedbackEditor)
    expect((input(restored[0]).element as HTMLInputElement).value).toBe('原条目的草稿')
    expect((input(restored[1]).element as HTMLInputElement).value).toBe('')
    expect(feedbackApi.save).not.toHaveBeenCalled(); wrapper.unmount()
  })

  it('同一任务换文件后，原文件迟到保存不会覆盖新文件输入', async () => {
    const sample = samples[1]
    const wrapper = mount(SpecialistReviewResultTab, { props: { taskId: sample.taskId, result: clone(sample.resultView) }, ...options })
    await flushPromises()
    let resolveOld!: (value: FeedbackView) => void
    vi.mocked(feedbackApi.save).mockReturnValueOnce(new Promise(resolve => { resolveOld = resolve }))
    const first = wrapper.findAllComponents(FeedbackEditor)[0]
    await input(first).setValue('原文件保存内容'); await button(first, '保存反馈').trigger('click')
    const other = clone(sample.resultView)
    other.id = samples[2].resultView.id; other.targetId = samples[2].targetId
    other.items = other.items!.map((item, index) => ({ ...item, id: samples[2].feedbacks[index].subjectId }))
    await wrapper.setProps({ result: other }); await flushPromises()
    await input(wrapper.findAllComponents(FeedbackEditor)[0]).setValue('新文件未保存内容')
    resolveOld({ ...sample.feedbacks[0], assessment: null, comment: '原文件保存内容', revision: 1 })
    await flushPromises()
    expect((input(wrapper.findAllComponents(FeedbackEditor)[0]).element as HTMLInputElement).value).toBe('新文件未保存内容')
    expect(wrapper.findAllComponents(FeedbackEditor)[0].text()).toContain('尚未保存')
    await wrapper.setProps({ result: clone(sample.resultView) }); await flushPromises()
    expect((input(wrapper.findAllComponents(FeedbackEditor)[0]).element as HTMLInputElement).value).toBe('原文件保存内容')
    expect(wrapper.findAllComponents(FeedbackEditor)[0].text()).toContain('已保存')
    wrapper.unmount()
  })

  it('保存失败可用原输入重试，成功后只清理对应条目的草稿', async () => {
    const sample = samples[1]
    const wrapper = mount(SpecialistReviewResultTab, { props: { taskId: sample.taskId, result: clone(sample.resultView) }, ...options })
    await flushPromises()
    const editors = wrapper.findAllComponents(FeedbackEditor)
    await input(editors[0]).setValue('待重试的评论'); await input(editors[1]).setValue('另一条未保存')
    vi.mocked(feedbackApi.save).mockRejectedValueOnce(new Error('暂时保存失败'))
    await button(editors[0], '保存反馈').trigger('click'); await flushPromises()
    expect((input(editors[0]).element as HTMLInputElement).value).toBe('待重试的评论')
    expect(editors[0].find('a-alert-stub').attributes('message')).toContain('暂时保存失败')
    await button(editors[0], '保存反馈').trigger('click'); await flushPromises()
    expect(saved.get(key('PATTERN', sample.feedbacks[0].subjectId))?.comment).toBe('待重试的评论')
    expect((input(editors[1]).element as HTMLInputElement).value).toBe('另一条未保存')
    expect(hasDirtyFeedbackDrafts(sample.taskId)).toBe(true); wrapper.unmount()
  })
})
