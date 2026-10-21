// @vitest-environment happy-dom
import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import SpecialistResultView from '@/components/SpecialistResultView.vue'
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
  it('问题结果展示原因、建议、保存位置并发出打开代码事件', async () => {
    const wrapper = mount(SpecialistResultView, {
      props: { result: issueResult() },
      global: { stubs: namedAntStubs }
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
  })

  it('未完成结果明确提示不能作为完整结论', () => {
    const result = issueResult()
    result.completeness = 'INCOMPLETE'
    const wrapper = mount(SpecialistResultView, {
      props: { result },
      global: { stubs: namedAntStubs }
    })

    expect(wrapper.html()).toContain('文件未完成')
    expect(wrapper.html()).toContain('不能作为完整文件结论')
  })
})
