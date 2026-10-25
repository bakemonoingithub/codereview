// @vitest-environment happy-dom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import CodeReferenceModal from '@/components/CodeReferenceModal.vue'
import { namedAntStubs } from '@/testUtils/antStubs'

vi.mock('@/api/v2', () => ({
  repositoryApi: { file: vi.fn() },
  tasksApi: { material: vi.fn() }
}))

import { repositoryApi, tasksApi } from '@/api/v2'

const fullLines = Array.from({ length: 40 }, (_, index) => `line ${index + 1}`)
const sha = 'a'.repeat(40)

const refs = [
  { materialId: 'm1', path: 'src/A.java', sha, side: 'FILE', startLine: 10, endLine: 12 },
  { materialId: 'm1', path: 'src/A.java', sha, side: 'FILE', startLine: 20, endLine: 20 },
  { materialId: 'm1', path: 'src/A.java', sha, side: 'FILE', startLine: 30, endLine: 31 },
  // 同一问题跨文件：本弹窗只接收当前文件的范围。
  { materialId: 'm9', path: 'src/B.java', sha, side: 'FILE', startLine: 5, endLine: 5 }
]

function saved(id = 'm1', start = 8, end = 35) {
  return {
    metadata: {
      id, path: 'src/A.java', sha, side: 'FILE', startLine: start, endLine: end,
      role: 'TARGET', disposition: 'SENT', completeness: 'COMPLETE',
      diagnostic: null, contentHash: '', byteSize: 0, sent: true
    },
    content: Array.from({ length: end - start + 1 }, (_, index) => `line ${start + index}`).join('\n'),
    complete: true
  }
}

const stubs = {
  ...namedAntStubs,
  'a-modal': {
    props: ['open'],
    template: '<div v-if="open"><slot /></div>'
  },
  'a-radio-group': {
    props: ['value'],
    emits: ['update:value', 'change'],
    template: '<div><slot /></div>'
  },
  'a-radio-button': {
    template: '<button type="button"><slot /></button>'
  }
}

const state = (wrapper: any) => wrapper.vm.$.setupState as any

async function start(overrides: Record<string, unknown> = {}) {
  const wrapper = mount(CodeReferenceModal, {
    props: {
      open: true,
      taskId: 't1',
      projectId: 'p1',
      scope: 'issue',
      path: 'src/A.java',
      sha,
      side: 'FILE',
      references: refs,
      activeStart: 10,
      activeEnd: 12,
      evidenceCount: 1,
      ...overrides
    },
    global: { stubs }
  })
  await flushPromises()
  await nextTick()
  return wrapper
}

function button(wrapper: any, text: string) {
  return wrapper.findAll('a-button-stub').find((item: any) => item.text().includes(text))
}

beforeEach(() => {
  vi.resetAllMocks()
  vi.mocked(repositoryApi.file).mockResolvedValue({ content: fullLines.join('\n') } as any)
  vi.mocked(tasksApi.material).mockResolvedValue(saved() as any)
})

describe('代码引用阅读', () => {
  it('按文件读取固定版本全文，合并同问题证据并打开当前范围', async () => {
    const wrapper = await start()

    expect(repositoryApi.file).toHaveBeenCalledWith('p1', sha, 'src/A.java')
    expect(tasksApi.material).toHaveBeenCalledWith('t1', 'm1')
    expect(wrapper.text()).toContain('定位 第 10–12 行')
    expect(wrapper.text()).toContain('本问题在此文件的 3 处引用（含 1 处辅助证据）')
    expect(wrapper.text()).toContain('1 / 3')
    expect(wrapper.text()).toContain('全文件 1–40 行')
    expect(wrapper.findAll('.range')).toHaveLength(3)
    expect(wrapper.findAll('.range.active')).toHaveLength(1)
    expect(wrapper.find('.code-gutter').text()).toContain('1')
    expect(wrapper.find('pre').text()).toContain('line 10')
    wrapper.unmount()
  })

  it('上一处/下一处更新定位、计数与当前高亮', async () => {
    const wrapper = await start()
    await button(wrapper, '下一处').trigger('click')
    await nextTick()

    expect(wrapper.text()).toContain('定位 第 20 行')
    expect(wrapper.text()).toContain('2 / 3')
    expect(state(wrapper).activeIndex).toBe(1)
    wrapper.unmount()
  })

  it('结果引用保留 Agent 引用标识，并随导航切换', async () => {
    const wrapper = await start({
      scope: 'result',
      references: refs.slice(0, 3).map((ref, index) => ({ ...ref, label: `E${index + 1}` })),
      activeStart: 20,
      activeEnd: 20
    })
    expect(wrapper.find('.reader-head').text()).toContain('E2')
    expect(wrapper.text()).toContain('本结果在此文件的 3 处引用')
    await button(wrapper, '下一处').trigger('click')
    await flushPromises()
    expect(wrapper.find('.reader-head').text()).toContain('E3')
    expect(wrapper.text()).toContain('定位 第 30–31 行')
    wrapper.unmount()
  })

  it('未记录 SHA 的 Agent 证据可直接阅读保存片段', async () => {
    const wrapper = await start({
      scope: 'result', sha: '', initialMode: 'saved',
      references: [{ ...refs[0], sha: undefined, label: 'E1' }]
    })
    expect(repositoryApi.file).not.toHaveBeenCalled()
    expect(tasksApi.material).toHaveBeenCalledWith('t1', 'm1')
    expect(wrapper.find('.reader-head').text()).toContain('E1')
    expect(wrapper.find('pre').text()).toContain('line 10')
    expect(wrapper.text()).toContain('实际发送 · 第 8–35 行')
    wrapper.unmount()
  })

  it('报告模式默认阅读冻结正文，不回查当前任务材料或仓库', async () => {
    const frozen = { ...saved(), content: '冻结的正文' }
    const wrapper = await start({ frozenMaterials: [frozen] })
    expect(wrapper.find('pre').text()).toBe('冻结的正文')
    expect(wrapper.text()).toContain('报告冻结')
    expect(tasksApi.material).not.toHaveBeenCalled()
    expect(repositoryApi.file).not.toHaveBeenCalled()
    await wrapper.setProps({ frozenMaterials: [{ ...frozen, content: '另一来源的冻结正文' }] })
    await flushPromises()
    expect(wrapper.find('pre').text()).toBe('另一来源的冻结正文')
    state(wrapper).mode = 'full'
    await flushPromises()
    expect(repositoryApi.file).toHaveBeenCalledWith('p1', sha, 'src/A.java')
    state(wrapper).mode = 'saved'
    await flushPromises()
    expect(wrapper.find('pre').text()).toBe('另一来源的冻结正文')
    expect(tasksApi.material).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('报告缺失冻结正文时显示缺失，不以当前材料补齐', async () => {
    const wrapper = await start({ frozenMaterials: [] })
    expect(wrapper.text()).toContain('报告快照未保存该材料的可用正文')
    expect(tasksApi.material).not.toHaveBeenCalled()
    expect(repositoryApi.file).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('保存片段使用材料原始行号，并可切回全文', async () => {
    const wrapper = await start()
    state(wrapper).mode = 'saved'
    await flushPromises()
    await nextTick()

    expect(wrapper.text()).toContain('实际发送 · 第 8–35 行')
    expect(wrapper.find('.code-gutter').text()).toContain('8')
    expect(wrapper.find('pre').text()).toContain('line 8')

    state(wrapper).mode = 'full'
    await flushPromises()
    await nextTick()
    expect(wrapper.find('.code-gutter').text()).toContain('1')
    wrapper.unmount()
  })

  it('片段不覆盖当前引用时给出说明，并提示未覆盖的引用数', async () => {
    vi.mocked(tasksApi.material).mockResolvedValue(saved('m1', 20, 25) as any)
    const wrapper = await start()
    state(wrapper).mode = 'saved'
    await flushPromises()
    await nextTick()

    expect(wrapper.text()).toContain('当前引用第 10–12 行不在本次发送的片段（第 20–25 行）内')
    expect(wrapper.text()).toContain('2 处引用不在发送片段内')
    wrapper.unmount()
  })

  it('片段内切到片段外引用时自动回到全文', async () => {
    vi.mocked(tasksApi.material).mockResolvedValue(saved('m1', 20, 25) as any)
    const wrapper = await start({ activeStart: 20, activeEnd: 20 })
    state(wrapper).mode = 'saved'
    await flushPromises()
    await nextTick()

    await button(wrapper, '下一处').trigger('click')
    await flushPromises()
    await nextTick()
    expect(state(wrapper).mode).toBe('full')
    expect(wrapper.text()).toContain('定位 第 30–31 行')
    expect(wrapper.text()).toContain('3 / 3')
    wrapper.unmount()
  })

  it('全文读取失败时保留引用与计数，可重试恢复', async () => {
    vi.mocked(repositoryApi.file).mockRejectedValueOnce(new Error('仓库读取失败'))
    const wrapper = await start()

    expect(wrapper.text()).toContain('固定版本全文读取失败')
    expect(wrapper.text()).toContain('仓库读取失败')
    expect(wrapper.text()).toContain('1 / 3')

    await button(wrapper, '重试').trigger('click')
    await flushPromises()
    await nextTick()
    expect(repositoryApi.file).toHaveBeenCalledTimes(2)
    expect(wrapper.find('pre').text()).toContain('line 10')
    wrapper.unmount()
  })

  it('保存片段失败时可回到全文，不把缺失当空文件', async () => {
    vi.mocked(tasksApi.material).mockRejectedValue(new Error('片段读取失败'))
    const wrapper = await start()
    state(wrapper).mode = 'saved'
    await flushPromises()
    await nextTick()

    expect(wrapper.text()).toContain('保存片段读取失败')
    await button(wrapper, '查看固定版本全文').trigger('click')
    await flushPromises()
    await nextTick()
    expect(wrapper.find('pre').text()).toContain('line 10')
    wrapper.unmount()
  })
})
