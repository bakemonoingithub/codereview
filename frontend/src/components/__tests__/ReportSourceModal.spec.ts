// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import FeedbackEditor from '@/components/FeedbackEditor.vue'
import SpecialistReviewResultTab from '@/components/SpecialistReviewResultTab.vue'
import ReportSourceModal from '@/components/ReportSourceModal.vue'
import AgentResultPanel from '@/components/AgentResultPanel.vue'
import CodeReferenceModal from '@/components/CodeReferenceModal.vue'
import { namedAntStubs, silentStub } from '@/testUtils/antStubs'

vi.mock('@/api/v2', () => ({ reportsApi: { source: vi.fn() }, feedbackApi: { get: vi.fn(), save: vi.fn() }, tasksApi: { material: vi.fn(), detail: vi.fn() }, repositoryApi: { file: vi.fn() } }))
import { feedbackApi, reportsApi, tasksApi, repositoryApi } from '@/api/v2'

const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/', component: { template: '<div />' } }, { name: 'review-detail', path: '/reviews/:id', component: { template: '<div />' } }] })
const options = { global: { plugins: [router], stubs: { ...namedAntStubs, 'a-modal': silentStub('modal') } } }
function snapshot() {
  return { ordinal: 1, resultId: 'r1', snapshot: {
    kind: 'ISSUE', taskId: 'task-1', project: { id: 'p1', name: '项目' },
    completeness: 'COMPLETE', schemaVersion: '1', resultId: 'r1', targetId: 'target',
    issues: [{ ordinal: 1, title: '问题', locations: [], feedback: { assessment: 'ACCURATE', comment: '当时评价' } }],
    result: {
      body: { issues: [{ title: '问题', locations: [], changeRelation: '删除幂等保护', evidence: [{ materialId: 'aux', startLine: 20, endLine: 20 }] }] },
      provenance: { materials: [{ id: 'aux', path: 'Guard.java', sha: 'base-sha', side: 'BEFORE', role: 'EVIDENCE' }],
        materialGaps: [{ code: 'MISSING', subject: 'Parent', detail: '父类源码未取得' }] }
    },
    materials: [{ id: 'aux', path: 'Guard.java', sha: 'base-sha', side: 'BEFORE', startLine: 20, endLine: 20, content: 'guard();' }]
  } }
}
async function start() {
  const wrapper = mount(ReportSourceModal, { props: { open: true, reportId: 'report', ordinal: 1 }, ...options })
  await flushPromises()
  return wrapper
}
beforeEach(() => {
  vi.resetAllMocks()
  vi.mocked(reportsApi.source).mockResolvedValue(snapshot() as any)
  vi.mocked(tasksApi.material).mockResolvedValue({ content: '当前材料', complete: true } as any)
  vi.mocked(repositoryApi.file).mockResolvedValue({ content: '当前仓库代码' } as any)
})

afterEach(() => {
  expect(feedbackApi.get).not.toHaveBeenCalled()
  expect(feedbackApi.save).not.toHaveBeenCalled()
  expect(tasksApi.detail).not.toHaveBeenCalled()
})

describe('报告冻结来源', () => {
  it('第 2 版快照在对应模式卡底显示冻结反馈，忽略旧整份评价', async () => {
    const data: any = snapshot()
    data.snapshot.snapshotVersion = 2; data.snapshot.kind = 'PATTERN'; data.snapshot.issues = []
    data.snapshot.result.body = { patterns: [{ name: '策略模式', evidence: [] }, { name: '外观模式', evidence: [] }] }
    data.snapshot.items = [{ id: 'p1', kind: 'PATTERN', ordinal: 1, feedback: { assessment: 'ACCURATE', comment: '冻结评论' } },
      { id: 'p2', kind: 'PATTERN', ordinal: 2, feedback: null }]
    data.snapshot.resultFeedback = { assessment: 'INACCURATE', comment: '历史整份意见' }
    vi.mocked(reportsApi.source).mockResolvedValue(data)
    const wrapper = await start()
    expect(wrapper.find('.feedback-summary').text()).toContain('准确 1 · 不准确 0 · 无法判断 0 · 未反馈 1')
    expect(wrapper.findAll('.finding')[0].text()).toContain('策略模式')
    expect(wrapper.findAll('.finding')[0].find('.frozen-feedback').text()).toContain('冻结评论')
    expect(wrapper.text()).not.toContain('历史整份意见')
    expect(tasksApi.material).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('来源弹窗展示完整的冻结分支与修改前后版本', async () => {
    const data: any = snapshot()
    data.snapshot.task = { branch: 'release', commitSha: 'c'.repeat(40), baseSha: 'd'.repeat(40) }
    data.snapshot.target = { path: 'A.java', baseSha: 'b'.repeat(40), headSha: 'a'.repeat(40) }
    vi.mocked(reportsApi.source).mockResolvedValue(data)
    const wrapper = await start()
    await wrapper.findAll('.reader-tabs button')[1].trigger('click')
    const versions = wrapper.find('[aria-label="来源源码版本"]')
    expect(versions.text()).toContain('release')
    expect(versions.text()).toContain('修改前版本')
    expect(versions.text()).toContain('修改后版本')
    expect(versions.findAll('code').map(code => code.text())).toEqual(['release', 'b'.repeat(40), 'a'.repeat(40), 'A.java'])
    expect(tasksApi.material).not.toHaveBeenCalled()
    expect(repositoryApi.file).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('版本只使用冻结字段，单版本及缺失版本有明确展示', async () => {
    const data: any = snapshot()
    data.snapshot.task = { commitSha: 'saved-version' }
    vi.mocked(reportsApi.source).mockResolvedValue(data)
    const wrapper = await start()
    await wrapper.findAll('.reader-tabs button')[1].trigger('click')
    const versions = wrapper.find('[aria-label="来源源码版本"]')
    expect(versions.text()).toContain('源码版本')
    expect(versions.text()).toContain('saved-version')
    expect(versions.text()).not.toContain('修改前版本')
    vi.mocked(reportsApi.source).mockResolvedValue(snapshot() as any)
    await wrapper.setProps({ ordinal: 2 })
    await flushPromises()
    await wrapper.findAll('.reader-tabs button')[1].trigger('click')
    expect(wrapper.find('[aria-label="来源源码版本"]').text()).toContain('版本未记录')
    wrapper.unmount()
  })

  it('反馈计数根据冻结评价展示，问题审查与结果级反馈分别统计', async () => {
    const data: any = snapshot()
    data.snapshot.issues = ['ACCURATE', 'INACCURATE', 'UNKNOWN', null].map((assessment, index) => ({
      id: `issue-${index}`, ordinal: index + 1, title: `问题 ${index}`, locations: [], feedback: assessment ? { assessment } : null
    }))
    data.snapshot.result.body.issues = data.snapshot.issues.map((issue: any) => ({ ...issue, evidence: [] }))
    vi.mocked(reportsApi.source).mockResolvedValue(data)
    const wrapper = await start()
    expect(wrapper.findAll('.finding')).toHaveLength(4)
    expect(wrapper.find('.feedback-summary').text()).toContain('准确 1 · 不准确 1 · 无法判断 1 · 未反馈 1')

    data.snapshot.kind = 'AGENT'
    data.snapshot.resultFeedback = { assessment: 'UNKNOWN', comment: '冻结评论' }
    vi.mocked(reportsApi.source).mockResolvedValue(data)
    await wrapper.setProps({ ordinal: 2 })
    await flushPromises()
    expect(wrapper.find('.feedback-summary').text()).toContain('准确 0 · 不准确 0 · 无法判断 1 · 未反馈 0')
    expect(wrapper.find('.feedback-summary').text()).not.toContain('个问题')
    expect(wrapper.find('.legacy-feedback').text()).toContain('冻结评论')
    expect(wrapper.find('.legacy-feedback').text()).toContain('无法判断')
    wrapper.unmount()
  })

  it('展示冻结的辅助证据、变更关系与材料缺口，并按材料 ID 还原位置', async () => {
    const wrapper = await start()
    expect(wrapper.text()).toContain('删除幂等保护')
    expect(wrapper.text()).toContain('Guard.java : 20-20 · 修改前')
    expect(wrapper.text()).toContain('父类源码未取得')
    const link = wrapper.findAll('a-button-stub').find((button) => button.text().includes('Guard.java : 20-20'))!
    await link.trigger('click')
    await flushPromises()
    const reader = wrapper.findComponent(CodeReferenceModal)
    expect(reader.text()).toContain('报告冻结')
    expect(reader.find('pre').text()).toContain('guard();')
    expect(reader.text()).toContain('base-sha')
    expect(tasksApi.material).not.toHaveBeenCalled()
    expect(repositoryApi.file).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('冻结材料列表复用代码阅读器，反馈使用当时快照', async () => {
    const wrapper = await start()
    const tab = wrapper.findAll('.reader-tabs button').find((button) => button.text() === '输入与配置')!
    await tab.trigger('click')
    expect(wrapper.text()).toContain('base-sha')
    expect(wrapper.text()).toContain('父类源码未取得')
    await wrapper.find('.material-row a-button-stub').trigger('click')
    await flushPromises()
    expect(wrapper.findComponent(CodeReferenceModal).find('pre').text()).toContain('guard();')
    const feedback = wrapper.findAll('.reader-tabs button').find((button) => button.text() === '审查结果')!
    await feedback.trigger('click')
    expect(wrapper.find('.finding .frozen-feedback').text()).toContain('当时评价')
    expect(tasksApi.material).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('Agent 冻结结果复用详情页结果组件，正文引用打开冻结代码', async () => {
    const data: any = snapshot()
    data.snapshot.kind = 'AGENT'
    data.snapshot.result.body = { title: '冻结的 Agent 结论', summary: '摘要', answerStatus: 'INSUFFICIENT_EVIDENCE',
      markdown: '需要核对保护逻辑。[E1]', unresolved: ['父类未取得'],
      citations: [{ id: 'E1', materialId: 'aux', path: 'Guard.java', sha: 'base-sha', side: 'BEFORE', startLine: 20, endLine: 20 }] }
    vi.mocked(reportsApi.source).mockResolvedValue(data)
    const wrapper = await start()
    expect(wrapper.findComponent(AgentResultPanel).exists()).toBe(true)
    expect(wrapper.text()).toContain('冻结的 Agent 结论')
    expect(wrapper.text()).toContain('父类未取得')
    await wrapper.find('.agent-evidence button').trigger('click')
    await flushPromises()
    expect(wrapper.findComponent(CodeReferenceModal).find('pre').text()).toContain('guard();')
    expect(tasksApi.material).not.toHaveBeenCalled()
    expect(repositoryApi.file).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('旧 Agent 纯文本快照仍可阅读，不伪造结果状态', async () => {
    const data: any = snapshot()
    data.snapshot.kind = 'AGENT'
    data.snapshot.result.body = '# 旧版冻结结论'
    vi.mocked(reportsApi.source).mockResolvedValue(data)
    const wrapper = await start()
    expect(wrapper.text()).toContain('旧版冻结结论')
    expect(wrapper.text()).toContain('状态未记录')
    wrapper.unmount()
  })

  it('切换来源后迟到快照不能覆盖新来源', async () => {
    let resolveOld!: (value: any) => void
    let resolveNew!: (value: any) => void
    vi.mocked(reportsApi.source).mockReturnValueOnce(new Promise((resolve) => { resolveOld = resolve }))
      .mockReturnValueOnce(new Promise((resolve) => { resolveNew = resolve }))
    const wrapper = mount(ReportSourceModal, { props: { open: true, reportId: 'report', ordinal: 1 }, ...options })
    await wrapper.setProps({ ordinal: 2 })
    const newer: any = snapshot()
    newer.snapshot.kind = 'AGENT'
    newer.snapshot.result.body = '新来源正文'
    resolveNew(newer)
    await flushPromises()
    resolveOld(snapshot())
    await flushPromises()
    expect(wrapper.text()).toContain('新来源正文')
    expect(wrapper.text()).not.toContain('删除幂等保护')
    wrapper.unmount()
  })

  it('精简头部，只在输入页显示版本和记录，原详情另开并定位文件', async () => {
    const data: any = snapshot()
    data.snapshot.task = { id: 'task-1', branch: 'dev', commitSha: 'saved-sha' }
    data.snapshot.target = { path: 'src/TaskService.java' }
    data.snapshot.resultCreatedAt = '2026-10-26T16:52:08'
    data.snapshot.frozenAt = '2026-10-26T16:54:37.862422500'
    vi.mocked(reportsApi.source).mockResolvedValue(data)
    const wrapper = await start()
    const header = wrapper.find('.source-header')
    expect(header.text()).toContain('TaskService.java')
    expect(header.text()).not.toMatch(/task-1|r1|saved-sha|16:54:37/)
    expect(wrapper.findAll('[role="tab"]').map(tab => tab.text())).toEqual(['审查结果', '输入与配置'])
    expect(wrapper.findComponent(SpecialistReviewResultTab).props('readOnly')).toBe(true)
    expect(wrapper.findComponent(FeedbackEditor).exists()).toBe(false)
    const link = wrapper.find('.original-detail')
    expect(link.attributes()).toMatchObject({ href: '/reviews/task-1?targetId=target', target: '_blank', rel: 'noopener noreferrer' })
    await wrapper.findAll('[role="tab"]')[1].trigger('click')
    expect(wrapper.text()).toContain('saved-sha')
    expect(wrapper.find('[aria-label="冻结记录"]').text()).toContain('2026-10-26 16:54:37')
    expect(wrapper.find('[aria-label="冻结记录"]').text()).not.toContain('862422500')
    expect(wrapper.text()).toContain('未保存完整的模型、通用规则和本次要求')
    expect(wrapper.text()).not.toContain('未提供给模型')
    wrapper.unmount()
  })

  it.each(['PATTERN', 'COUPLING'])('%s 的乱序条目按 ordinal 绑定，只有评论也保留', async kind => {
    const data: any = snapshot()
    data.snapshot.kind = kind; data.snapshot.snapshotVersion = 2; data.snapshot.issues = []
    data.snapshot.result.body = kind === 'PATTERN'
      ? { patterns: [{ name: '第一条', evidence: [] }, { name: '第二条', evidence: [] }] }
      : { observations: [{ title: '第一条', explanation: '说明', evidence: [] }, { title: '第二条', explanation: '说明', evidence: [] }] }
    data.snapshot.items = [
      { id: 'second', kind, ordinal: 2, feedback: { assessment: null, comment: '第二条的独立评论' } },
      { id: 'first', kind, ordinal: 1, feedback: { assessment: 'INACCURATE', comment: '第一条不准确' } }
    ]
    vi.mocked(reportsApi.source).mockResolvedValue(data)
    const wrapper = await start()
    const findings = wrapper.findAll('.finding')
    expect(findings[0].find('.frozen-feedback').text()).toContain('第一条不准确')
    expect(findings[1].find('.frozen-feedback').text()).toContain('第二条的独立评论')
    expect(findings[1].find('.assessment').text()).toBe('未反馈')
    expect(wrapper.find('.feedback-summary').text()).toContain('不准确 1 · 无法判断 0 · 未反馈 1')
    wrapper.unmount()
  })

  it.each(['PATTERN', 'COUPLING'])('%s 的旧整份评价不拆到各卡底，缺少索引不提示可编辑', async kind => {
    const data: any = snapshot()
    data.snapshot.kind = kind; data.snapshot.issues = []
    data.snapshot.result.body = kind === 'PATTERN' ? { patterns: [{ name: '旧模式', evidence: [] }] }
      : { observations: [{ title: '旧关系', evidence: [] }] }
    data.snapshot.resultFeedback = { assessment: 'UNKNOWN', comment: '历史整份意见' }
    vi.mocked(reportsApi.source).mockResolvedValue(data)
    const wrapper = await start()
    expect(wrapper.find('.finding .frozen-feedback').exists()).toBe(false)
    expect(wrapper.find('.legacy-feedback').text()).toContain('历史整份意见')
    expect(wrapper.find('.legacy-feedback').text()).toContain('未分配给各条结论')
    expect(wrapper.html()).not.toContain('暂不能反馈')
    expect(wrapper.findComponent(FeedbackEditor).exists()).toBe(false)
    wrapper.unmount()
  })

  it('冻结问题按序号匹配反馈而不是数组位置，评论中的 HTML 仅作文本', async () => {
    const data: any = snapshot()
    data.snapshot.issues = [{ ordinal: 2, id: 'two', feedback: null },
      { ordinal: 1, id: 'one', feedback: { assessment: 'ACCURATE', comment: '<img src=x onerror=alert(1)>' } }]
    data.snapshot.result.body.issues = [{ title: '第一个问题', locations: [] }, { title: '第二个问题', locations: [] }]
    vi.mocked(reportsApi.source).mockResolvedValue(data)
    const wrapper = await start()
    expect(wrapper.findAll('.finding')[0].text()).toContain('<img src=x onerror=alert(1)>')
    expect(wrapper.findAll('.finding')[1].text()).toContain('当时尚未保存评价或评论')
    expect(wrapper.find('img').exists()).toBe(false)
    wrapper.unmount()
  })

  it('材料未保存正文时明确失败，不回查当前材料，固定版本全文单独读取', async () => {
    const data: any = snapshot(); delete data.snapshot.materials[0].content
    data.snapshot.materials[0].completeness = 'PARTIAL'
    vi.mocked(reportsApi.source).mockResolvedValue(data)
    const wrapper = await start()
    await wrapper.findAll('[role="tab"]')[1].trigger('click')
    expect(wrapper.text()).toContain('快照未保存正文')
    expect(wrapper.text()).toContain('所选范围未完整返回')
    await wrapper.find('.material-row a-button-stub').trigger('click'); await flushPromises()
    const reader = wrapper.findComponent(CodeReferenceModal)
    expect(reader.text()).toContain('无法读取冻结片段')
    expect(tasksApi.material).not.toHaveBeenCalled()
    expect(repositoryApi.file).not.toHaveBeenCalled()
    await reader.findAll('a-button-stub').find(button => button.text() === '查看固定版本全文')!.trigger('click')
    await flushPromises()
    expect(repositoryApi.file).toHaveBeenCalledWith('p1', 'base-sha', 'Guard.java')
    reader.vm.$emit('update:open', false); await flushPromises()
    expect(wrapper.emitted('update:open')).toBeUndefined()
    wrapper.unmount()
  })

  it('读取失败可重试；关闭后的迟到响应与重新打开的页签相互隔离', async () => {
    vi.mocked(reportsApi.source).mockRejectedValueOnce(new Error('来源读取失败'))
    const wrapper = await start()
    expect(wrapper.html()).toContain('来源读取失败')
    await wrapper.findAll('a-button-stub').find(button => button.text() === '重试')!.trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('删除幂等保护')
    await wrapper.findAll('[role="tab"]')[1].trigger('click')
    await wrapper.setProps({ open: false })
    let resolveOld!: (value: any) => void
    vi.mocked(reportsApi.source).mockReturnValueOnce(new Promise(resolve => { resolveOld = resolve }))
    await wrapper.setProps({ open: true })
    await wrapper.find('.close-button').trigger('click')
    await wrapper.setProps({ open: false })
    resolveOld(snapshot()); await flushPromises()
    expect(wrapper.find('.reader-tabs').exists()).toBe(false)
    await wrapper.setProps({ open: true }); await flushPromises()
    expect(wrapper.find('[aria-selected="true"]').text()).toBe('审查结果')
    expect(wrapper.emitted('update:open')).toEqual([[false]])
    wrapper.unmount()
  })

  it('缺失任务和结果记录时不创建无效链接，空结果与空材料有说明', async () => {
    const data: any = snapshot()
    data.snapshot.taskId = ''; data.snapshot.result = null; data.snapshot.materials = []
    vi.mocked(reportsApi.source).mockResolvedValue(data)
    const wrapper = await start()
    expect(wrapper.find('.original-detail').exists()).toBe(false)
    expect(wrapper.html()).toContain('冻结快照中没有可浏览的结果')
    await wrapper.findAll('[role="tab"]')[1].trigger('click')
    expect(wrapper.html()).toContain('此来源没有保存代码材料')
    expect(wrapper.find('[aria-label="冻结记录"]').text()).toContain('未记录')
    wrapper.unmount()
  })

  it('页签支持键盘切换并重置内容滚动', async () => {
    const wrapper = await start()
    const scroller = wrapper.find('.reader-content').element as HTMLElement
    scroller.scrollTop = 120
    await wrapper.findAll('[role="tab"]')[0].trigger('keydown', { key: 'ArrowRight' })
    expect(wrapper.find('[aria-selected="true"]').text()).toBe('输入与配置')
    expect(scroller.scrollTop).toBe(0)
    await wrapper.findAll('[role="tab"]')[1].trigger('keydown', { key: 'Home' })
    expect(wrapper.find('[aria-selected="true"]').text()).toBe('审查结果')
    wrapper.unmount()
  })
})
