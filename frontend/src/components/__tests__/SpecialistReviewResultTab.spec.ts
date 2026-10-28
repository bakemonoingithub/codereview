// @vitest-environment happy-dom
import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import SpecialistReviewResultTab from '@/components/SpecialistReviewResultTab.vue'
import FeedbackEditor from '@/components/FeedbackEditor.vue'
import { namedAntStubs } from '@/testUtils/antStubs'
import type { ResultView } from '@/api/v2'

const location = { materialId: 'm1', startLine: 3, endLine: 5 }
const options = { global: { stubs: { ...namedAntStubs, FeedbackEditor: true } } }
function result(kind: ResultView['kind']): ResultView {
  return {
    id: 'result', targetId: 'target', kind, completeness: 'COMPLETE', schemaVersion: '1', createdAt: '',
    body: { summary: '摘要', limitations: ['限于已取得的材料'] }, issues: [], items: [],
    provenance: { materials: [{ id: 'm1', path: 'src/A.java', sha: 'fixed-version', side: 'BEFORE', role: 'TARGET' }] }
  }
}
function patterns(): ResultView {
  const data = result('PATTERN')
  data.body.patterns = [1, 2].map(() => ({ name: '同名候选', family: 'BEHAVIORAL', role: '上下文', explanation: '接口委托', evidence: [location] }))
  // 身份读取按 ordinal，不能因传输顺序不同而绑定到其他条目。
  data.items = [{ id: 'p2', kind: 'PATTERN', ordinal: 2 }, { id: 'p1', kind: 'PATTERN', ordinal: 1 }]
  return data
}

describe('专项审查结果 tab', () => {
  it('只读模式可使用冻结反馈插槽，缺少索引时不挂载编辑器或提示保存', () => {
    const data = patterns(); data.items = []
    const wrapper = mount(SpecialistReviewResultTab, {
      props: { result: data, taskId: 'task', readOnly: true },
      slots: { feedback: '<template #feedback="{ ordinal }"><p class="snapshot-feedback">冻结条目 {{ ordinal }}</p></template>' }, ...options
    })
    expect(wrapper.findAll('.finding .snapshot-feedback').map(item => item.text())).toEqual(['冻结条目 1', '冻结条目 2'])
    expect(wrapper.findAllComponents(FeedbackEditor)).toHaveLength(0)
    expect(wrapper.html()).not.toContain('缺少稳定标识')
    wrapper.unmount()
    const readonly = mount(SpecialistReviewResultTab, { props: { result: patterns(), taskId: 'task', readOnly: true }, ...options })
    expect(readonly.findAllComponents(FeedbackEditor)).toHaveLength(0)
    readonly.unmount()
  })

  it('模式卡按原始序号绑定独立服务端 ID，只在卡底显示反馈', () => {
    const wrapper = mount(SpecialistReviewResultTab, { props: { result: patterns(), taskId: 'task' }, ...options })
    expect(wrapper.text()).toContain('行为型')
    expect(wrapper.text()).toContain('目标承担的角色')
    expect(wrapper.text()).toContain('模式说明')
    expect(wrapper.text()).not.toContain('协议 v')
    expect(wrapper.findAllComponents(FeedbackEditor).map(item => [item.props('subjectKind'), item.props('subjectId')]))
      .toEqual([['PATTERN', 'p1'], ['PATTERN', 'p2']])
    expect(wrapper.find('.conclusion').findComponent(FeedbackEditor).exists()).toBe(false)
    wrapper.unmount()
  })

  it('问题区分说明、变更、建议、位置与辅助证据，严重度使用中文', async () => {
    const data = result('ISSUE')
    data.body.issues = [{ title: '重复提交', severity: 'HIGH', category: 'CORRECTNESS', reason: '缺少保护',
      suggestion: '增加幂等', changeRelation: '删除校验', locations: [location],
      evidence: [{ materialId: 'm2', startLine: 8, endLine: 9 }] }]
    data.issues = [{ id: 'issue-one', ordinal: 1 } as any]
    data.provenance.materials!.push({ id: 'm2', path: 'src/B.java', sha: 'other-version', side: 'AFTER', role: 'EVIDENCE' })
    const wrapper = mount(SpecialistReviewResultTab, { props: { result: data, taskId: 'task' }, ...options })
    for (const label of ['高严重度', '问题说明', '与本次变更的关系', '修改建议', '问题位置', '辅助证据']) expect(wrapper.text()).toContain(label)
    const links = wrapper.findAll('.reference')
    await links[0].trigger('click')
    expect(wrapper.emitted('open-code')?.[0]?.[0]).toMatchObject({ path: 'src/A.java', sha: 'fixed-version', side: 'BEFORE', references: [expect.objectContaining({ path: 'src/A.java' })] })
    await links[1].trigger('click')
    expect(wrapper.emitted('open-code')?.[1]?.[0]).toMatchObject({ path: 'src/B.java', sha: 'other-version', side: 'AFTER' })
    expect(wrapper.findComponent(FeedbackEditor).props('subjectId')).toBe('issue-one')
    wrapper.unmount()
  })

  it('耦合度分开程序事实与模型解读，每条解读独立反馈', () => {
    const data = result('COUPLING')
    data.body.observations = [{ title: '跨层依赖', explanation: '直接依赖存储', dependencyIds: ['dep'], evidence: [location] }]
    data.items = [{ id: 'c1', kind: 'COUPLING', ordinal: 1 }]
    data.provenance.couplingFacts = { resolvedOutgoingCount: 1, unresolvedTypeCount: 2, coverage: 0.33, sha: 'fixed', side: 'BEFORE',
      edges: [{ id: 'dep', sourceType: 'A', targetType: 'B', targetPath: 'src/B.java', relation: 'FIELD', line: 3 }], limitations: ['第三方未定位'] }
    const wrapper = mount(SpecialistReviewResultTab, { props: { result: data, taskId: 'task' }, ...options })
    expect(wrapper.text()).toContain('文件级依赖事实')
    expect(wrapper.text()).toContain('33%')
    expect(wrapper.text()).toContain('模型关系解读')
    expect(wrapper.text()).toContain('关联依赖')
    expect(wrapper.text()).toContain('第三方未定位')
    expect(wrapper.text()).not.toContain('调整建议')
    expect(wrapper.find('.facts').findComponent(FeedbackEditor).exists()).toBe(false)
    expect(wrapper.findComponent(FeedbackEditor).props()).toMatchObject({ subjectKind: 'COUPLING', subjectId: 'c1', conclusion: true })
    wrapper.unmount()
  })

  it('空结果、未完成结果及缺失索引均不提供虚假的保存入口', () => {
    for (const kind of ['ISSUE', 'PATTERN', 'COUPLING'] as const) {
      const wrapper = mount(SpecialistReviewResultTab, { props: { result: result(kind), taskId: 'task' }, ...options })
      expect(wrapper.findAllComponents(FeedbackEditor)).toHaveLength(0)
      wrapper.unmount()
    }
    const data = patterns(); data.completeness = 'INCOMPLETE'
    const wrapper = mount(SpecialistReviewResultTab, { props: { result: data, taskId: 'task' }, ...options })
    expect(wrapper.html()).toContain('不能作为完整文件结论')
    expect(wrapper.findAllComponents(FeedbackEditor)).toHaveLength(0)
    wrapper.unmount()
    data.completeness = 'COMPLETE'; data.items = []
    const missing = mount(SpecialistReviewResultTab, { props: { result: data, taskId: 'task' }, ...options })
    expect(missing.html()).toContain('缺少稳定标识')
    expect(missing.findAllComponents(FeedbackEditor)).toHaveLength(0)
    missing.unmount()
  })

  it('缺失代码材料禁用链接；未知分类保留原值，模型文本不会执行 HTML', async () => {
    const data = patterns(); data.body.patterns![0].family = 'CUSTOM'
    data.body.summary = '<img src=x onerror=alert(1)>'
    data.provenance.materials = []
    const wrapper = mount(SpecialistReviewResultTab, { props: { result: data, taskId: 'task' }, ...options })
    expect(wrapper.text()).toContain('CUSTOM')
    expect(wrapper.text()).toContain('材料不可用')
    expect(wrapper.find('img').exists()).toBe(false)
    expect(wrapper.find('.reference').attributes('disabled')).toBeDefined()
    await wrapper.find('.reference').trigger('click')
    expect(wrapper.emitted('open-code')).toBeUndefined()
    wrapper.unmount()
  })
})
