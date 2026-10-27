// @vitest-environment happy-dom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ReportSourceModal from '@/components/ReportSourceModal.vue'
import AgentResultPanel from '@/components/AgentResultPanel.vue'
import CodeReferenceModal from '@/components/CodeReferenceModal.vue'
import { namedAntStubs, silentStub } from '@/testUtils/antStubs'

vi.mock('@/api/v2', () => ({ reportsApi: { source: vi.fn() }, tasksApi: { material: vi.fn() }, repositoryApi: { file: vi.fn() } }))
import { reportsApi, tasksApi, repositoryApi } from '@/api/v2'

const options = { global: { stubs: { ...namedAntStubs, 'a-modal': silentStub('modal') } } }
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

describe('报告冻结来源', () => {
  it('来源弹窗展示完整的冻结分支与修改前后版本', async () => {
    const data: any = snapshot()
    data.snapshot.task = { branch: 'release', commitSha: 'c'.repeat(40), baseSha: 'd'.repeat(40) }
    data.snapshot.target = { path: 'A.java', baseSha: 'b'.repeat(40), headSha: 'a'.repeat(40) }
    vi.mocked(reportsApi.source).mockResolvedValue(data)
    const wrapper = await start()
    const versions = wrapper.find('[aria-label="来源源码版本"]')
    expect(versions.text()).toContain('release')
    expect(versions.text()).toContain('修改前版本')
    expect(versions.text()).toContain('修改后版本')
    expect(versions.findAll('code').map(code => code.text())).toEqual(['release', 'b'.repeat(40), 'a'.repeat(40)])
    expect(tasksApi.material).not.toHaveBeenCalled()
    expect(repositoryApi.file).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('版本只使用冻结字段，单版本及缺失版本有明确展示', async () => {
    const data: any = snapshot()
    data.snapshot.task = { commitSha: 'saved-version' }
    vi.mocked(reportsApi.source).mockResolvedValue(data)
    const wrapper = await start()
    const versions = wrapper.find('[aria-label="来源源码版本"]')
    expect(versions.text()).toContain('源码版本')
    expect(versions.text()).toContain('saved-version')
    expect(versions.text()).not.toContain('修改前版本')
    vi.mocked(reportsApi.source).mockResolvedValue(snapshot() as any)
    await wrapper.setProps({ ordinal: 2 })
    await flushPromises()
    expect(wrapper.find('[aria-label="来源源码版本"]').text()).toContain('版本未记录')
    wrapper.unmount()
  })

  it('反馈计数根据冻结评价展示，问题审查与结果级反馈分别统计', async () => {
    const data: any = snapshot()
    data.snapshot.issues = ['ACCURATE', 'INACCURATE', 'UNKNOWN', null].map((assessment, index) => ({
      id: `issue-${index}`, title: `问题 ${index}`, locations: [], feedback: assessment ? { assessment } : null
    }))
    vi.mocked(reportsApi.source).mockResolvedValue(data)
    const wrapper = await start()
    await wrapper.findAll('.reader-tabs button')[2].trigger('click')
    expect(wrapper.find('.feedback-summary').text()).toContain('4 个问题')
    expect(wrapper.find('.feedback-summary').text()).toContain('准确 1 · 不准确 1 · 无法判断 1 · 未反馈 1')

    data.snapshot.kind = 'AGENT'
    data.snapshot.resultFeedback = { assessment: 'UNKNOWN', comment: '冻结评论' }
    vi.mocked(reportsApi.source).mockResolvedValue(data)
    await wrapper.setProps({ ordinal: 2 })
    await flushPromises()
    await wrapper.findAll('.reader-tabs button')[2].trigger('click')
    expect(wrapper.find('.feedback-summary').text()).toContain('准确 0 · 不准确 0 · 无法判断 1 · 未反馈 0')
    expect(wrapper.find('.feedback-summary').text()).not.toContain('个问题')
    expect(wrapper.find('.feedback').text()).toContain('无法判断（冻结评论）')
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
    const tab = wrapper.findAll('.reader-tabs button').find((button) => button.text().startsWith('冻结材料'))!
    await tab.trigger('click')
    expect(wrapper.text()).toContain('base-sha')
    expect(wrapper.text()).toContain('父类源码未取得')
    await wrapper.find('.material-row a-button-stub').trigger('click')
    await flushPromises()
    expect(wrapper.findComponent(CodeReferenceModal).find('pre').text()).toContain('guard();')
    const feedback = wrapper.findAll('.reader-tabs button').find((button) => button.text() === '当时反馈')!
    await feedback.trigger('click')
    expect(wrapper.text()).toContain('准确（当时评价）')
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
})
