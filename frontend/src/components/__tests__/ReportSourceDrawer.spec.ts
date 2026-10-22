// @vitest-environment happy-dom
import { describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ReportSourceDrawer from '@/components/ReportSourceDrawer.vue'
import { namedAntStubs, silentStub } from '@/testUtils/antStubs'

vi.mock('@/api/v2', () => ({ reportsApi: { source: vi.fn() } }))
import { reportsApi } from '@/api/v2'

describe('报告冻结来源', () => {
  it('展示冻结的辅助证据、变更关系与材料缺口，并按材料 ID 还原位置', async () => {
    vi.mocked(reportsApi.source).mockResolvedValue({ ordinal: 1, resultId: 'r1', snapshot: {
      kind: 'ISSUE', issues: [{ ordinal: 1, title: '问题', locations: [] }],
      result: { body: { issues: [{ changeRelation: '删除幂等保护', evidence: [{ materialId: 'aux', startLine: 20, endLine: 20 }] }] },
        provenance: { materialGaps: [{ code: 'MISSING', subject: 'Parent', detail: '父类源码未取得' }] } },
      materials: [{ id: 'aux', path: 'Guard.java', sha: 'base-sha', side: 'BEFORE', startLine: 20, endLine: 20, content: 'guard();' }]
    } } as any)
    const wrapper = mount(ReportSourceDrawer, { props: { open: true, reportId: 'report', ordinal: 1 },
      global: { stubs: { ...namedAntStubs, 'a-drawer': silentStub('drawer'), 'a-descriptions': silentStub('descriptions'),
        'a-descriptions-item': silentStub('description'), 'a-collapse': silentStub('collapse'), 'a-collapse-panel': silentStub('panel') } } })
    await flushPromises()
    expect(wrapper.text()).toContain('删除幂等保护')
    expect(wrapper.text()).toContain('Guard.java:20-20（修改前 base-sha）')
    expect(wrapper.text()).toContain('父类源码未取得')
    wrapper.unmount()
  })
})
