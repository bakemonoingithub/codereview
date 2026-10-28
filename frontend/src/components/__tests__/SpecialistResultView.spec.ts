// @vitest-environment happy-dom
import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import SpecialistResultView from '@/components/SpecialistResultView.vue'
import FeedbackEditor from '@/components/FeedbackEditor.vue'
import { namedAntStubs } from '@/testUtils/antStubs'
import type { ResultView } from '@/api/v2'

function issueResult(): ResultView {
  return {
    id: 'r1',
    targetId: 'g1',
    kind: 'ISSUE',
    schemaVersion: '1',
    completeness: 'COMPLETE',
    createdAt: '2026-10-21T10:00:00',
    body: {
      summary: '订单重试可能重复扣款',
      issues: [{
        category: 'CORRECTNESS',
        severity: 'HIGH',
        title: '重复扣款',
        locations: [{ materialId: 'm1', startLine: 10, endLine: 12 }],
        reason: '重试会再次提交',
        suggestion: '增加幂等键',
        evidence: [],
        changeRelation: null
      }],
      limitations: ['第三方实现不可取得']
    },
    issues: [{
      id: 'i1',
      ordinal: 1,
      category: 'CORRECTNESS',
      severity: 'HIGH',
      title: '重复扣款',
      reason: '重试会再次提交',
      suggestion: '增加幂等键',
      locations: [{
        materialId: 'm1',
        path: 'src/Order.java',
        sha: 'a'.repeat(40),
        side: 'FILE',
        startLine: 10,
        endLine: 12
      }],
      sourceUnits: ['u1']
    }],
    provenance: {
      materials: [{
        id: 'm1',
        path: 'src/Order.java',
        sha: 'a'.repeat(40),
        side: 'FILE',
        role: 'TARGET'
      }]
    }
  }
}

describe('专项结果阅读', () => {
  it('报告预览显式启用条目反馈，公共默认仍保留旧式结果反馈', async () => {
    const result = issueResult(); result.kind = 'PATTERN'; result.issues = []
    result.body = { summary: '模式', patterns: [{ name: 'Strategy', family: 'BEHAVIORAL', role: 'context', explanation: 'why', evidence: [] }] }
    result.items = [{ id: 'stable-pattern', kind: 'PATTERN', ordinal: 1 }]
    const wrapper = mount(SpecialistResultView, { props: { result, taskId: 't1' }, global: { stubs: { ...namedAntStubs, FeedbackEditor: true } } })
    expect(wrapper.findComponent(FeedbackEditor).props('subjectKind')).toBe('RESULT')
    await wrapper.setProps({ itemFeedback: true })
    expect(wrapper.findAllComponents(FeedbackEditor)).toHaveLength(1)
    expect(wrapper.findComponent(FeedbackEditor).props()).toMatchObject({ subjectKind: 'PATTERN', subjectId: 'stable-pattern' })
    await wrapper.setProps({ readOnly: true })
    expect(wrapper.findAllComponents(FeedbackEditor)).toHaveLength(0)
    wrapper.unmount()
  })

  it('问题结果展示原因、建议、保存位置并发出打开代码事件', async () => {
    const wrapper = mount(SpecialistResultView, {
      props: { result: issueResult(), taskId: 't1' },
      global: { stubs: { ...namedAntStubs, FeedbackEditor: true } }
    })

    expect(wrapper.text()).toContain('订单重试可能重复扣款')
    expect(wrapper.text()).toContain('重试会再次提交')
    expect(wrapper.text()).toContain('增加幂等键')
    expect(wrapper.text()).toContain('src/Order.java : 10-12')

    const locationButton = wrapper.findAll('a-button-stub')
      .find((button) => button.text().includes('src/Order.java : 10-12'))
    await locationButton!.trigger('click')

    expect(wrapper.emitted('open-code')?.[0]?.[0]).toMatchObject({
      materialId: 'm1',
      path: 'src/Order.java',
      startLine: 10,
      endLine: 12
    })
    const payload = wrapper.emitted('open-code')?.[0]?.[0] as any
    expect(payload.scope).toBe('issue')
    expect(payload.evidenceCount).toBe(0)
    expect(payload.references).toEqual([{
      materialId: 'm1',
      path: 'src/Order.java',
      sha: 'a'.repeat(40),
      side: 'FILE',
      startLine: 10,
      endLine: 12
    }])
  })

  it('同一问题的位置与辅助证据合并，跨文件引用不混入', async () => {
    const result = issueResult()
    const issue = result.body.issues![0]
    issue.locations.push({ materialId: 'm1', startLine: 20, endLine: 21 })
    issue.evidence = [{ materialId: 'm1', startLine: 30, endLine: 31 }]
    result.provenance.materials!.push({
      id: 'm2', path: 'src/Other.java', sha: 'b'.repeat(40), side: 'FILE', role: 'AUX'
    })
    issue.locations.push({ materialId: 'm2', startLine: 5, endLine: 5 })

    const wrapper = mount(SpecialistResultView, {
      props: { result, taskId: 't1' },
      global: { stubs: { ...namedAntStubs, FeedbackEditor: true } }
    })
    const locationButton = wrapper.findAll('a-button-stub')
      .find((button) => button.text().includes('src/Order.java : 10-12'))
    await locationButton!.trigger('click')

    const payload = wrapper.emitted('open-code')?.[0]?.[0] as any
    expect(payload.scope).toBe('issue')
    expect(payload.evidenceCount).toBe(1)
    expect(payload.references).toHaveLength(3)
    expect(payload.references.map((ref: any) => [ref.path, ref.startLine, ref.endLine])).toEqual([
      ['src/Order.java', 10, 12],
      ['src/Order.java', 20, 21],
      ['src/Order.java', 30, 31]
    ])
    expect(payload.references.some((ref: any) => ref.path === 'src/Other.java')).toBe(false)
    wrapper.unmount()
  })

  it('未完成结果明确提示不能作为完整结论', () => {
    const result = issueResult()
    result.completeness = 'INCOMPLETE'
    const wrapper = mount(SpecialistResultView, {
      props: { result, taskId: 't1' },
      global: { stubs: { ...namedAntStubs, FeedbackEditor: true } }
    })

    expect(wrapper.html()).toContain('文件未完成')
    expect(wrapper.html()).toContain('不能作为完整文件结论')
  })
  it('耦合结果展示程序计算事实和未定位边界', () => {
    const result = issueResult(); result.kind = 'COUPLING'; result.body = { summary: '依赖分析', observations: [] }
    result.provenance.couplingFacts = { resolvedOutgoingCount: 2, unresolvedTypeCount: 1, coverage: 0.66,
      sha: 'fixed', side: 'FILE', edges: [{ id: 'dep-1', sourceType: 'Order', targetType: 'Payment', targetPath: 'Payment.java', relation: 'FIELD', line: 3 }], limitations: ['第三方类型未定位'] }
    const w = mount(SpecialistResultView, { props: { result, taskId: 't1' }, global: { stubs: { ...namedAntStubs, FeedbackEditor: true } } })
    expect(w.text()).toContain('仓库内已解析出向依赖：2'); expect(w.text()).toContain('未可靠定位类型：1')
    expect(w.text()).toContain('Order → Payment'); expect(w.text()).toContain('第三方类型未定位'); w.unmount()
  })
  it('独立显示保存的材料缺口，模型未复述时也不丢失', () => {
    const result = issueResult(); result.provenance.materialGaps = [{ code: 'NOT_AVAILABLE', subject: 'a.Base', detail: '当前版本未定位' }]
    const w = mount(SpecialistResultView, { props: { result, taskId: 't1' }, global: { stubs: { ...namedAntStubs, FeedbackEditor: true } } })
    expect(w.text()).toContain('NOT_AVAILABLE：a.Base，当前版本未定位'); w.unmount()
  })

  it('模型提供的恶意 HTML 只按文本展示', () => {
    const result = issueResult(); result.body.summary = '<img src=x onerror="alert(1)"><script>alert(1)</script>'
    const w = mount(SpecialistResultView, { props: { result, taskId: 't1' }, global: { stubs: { ...namedAntStubs, FeedbackEditor: true } } })
    expect(w.find('script').exists()).toBe(false); expect(w.find('img').exists()).toBe(false)
    expect(w.text()).toContain('<script>alert(1)</script>'); w.unmount()
  })

})
