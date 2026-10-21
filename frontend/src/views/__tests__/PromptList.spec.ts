// @vitest-environment happy-dom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { nextTick } from 'vue'
import { mount } from '@vue/test-utils'
import PromptList from '@/views/PromptList.vue'
import { attrsStub, namedAntStubs } from '@/testUtils/antStubs'

vi.mock('@/api/v2', () => ({
  promptsApi: {
    list: vi.fn(),
    detail: vi.fn(),
    versions: vi.fn(),
    diff: vi.fn(),
    create: vi.fn(),
    update: vi.fn(),
    addVersion: vi.fn(),
    remove: vi.fn()
  }
}))

import { promptsApi } from '@/api/v2'

const summary = {
  id: 'p1',
  name: '订单规则',
  purpose: 'SPECIALIST',
  tags: ['订单'],
  revision: 1,
  updatedAt: '2026-10-20T10:00:00'
}

const detail = {
  id: 'p1',
  name: '订单规则',
  description: '支付与订单状态',
  tags: ['订单'],
  purpose: 'SPECIALIST',
  currentVersion: { id: 'v1', versionNo: 1, content: '服务端正文', createdAt: '2026-10-20T10:00:00' },
  revision: 1,
  createdAt: '2026-10-20T10:00:00',
  updatedAt: '2026-10-20T10:00:00'
}

const options = {
  global: {
    stubs: {
      ...namedAntStubs,
      'a-modal': { ...attrsStub('a-modal-stub'), name: 'AModal' },
      'a-table': false,
      'a-table-column': false,
      DiffViewer: true
    }
  }
}

const flush = () => new Promise((resolve) => setTimeout(resolve, 0))

beforeEach(() => {
  vi.resetAllMocks()
  ;(promptsApi.list as any).mockResolvedValue({ items: [summary], nextCursor: null, hasMore: false })
  ;(promptsApi.detail as any).mockResolvedValue(detail)
})

function button(wrapper: any, label: string) {
  return wrapper.findAll('a-button-stub').find((b: any) => b.text() === label)!
}

function contentInput(wrapper: any) {
  return wrapper.find('a-textarea-stub input')
}

describe('PromptList 编辑与版本', () => {
  it('慢响应不覆盖用户已经输入的正文', async () => {
    let resolveDetail: (value: unknown) => void = () => {}
    ;(promptsApi.detail as any).mockReturnValue(
      new Promise((resolve) => {
        resolveDetail = resolve
      })
    )
    const wrapper = mount(PromptList, options)
    await flush()

    await button(wrapper, '编辑').trigger('click')
    await nextTick()
    await contentInput(wrapper).setValue('用户刚输入的正文')
    resolveDetail(detail)
    await flush()
    await nextTick()

    expect((contentInput(wrapper).element as HTMLInputElement).value).toBe('用户刚输入的正文')
  })

  it('正文变化时保存为新版本，旧版本不被覆盖', async () => {
    ;(promptsApi.update as any).mockResolvedValue({ ...detail, revision: 2 })
    ;(promptsApi.addVersion as any).mockResolvedValue({ ...detail, revision: 3 })
    const wrapper = mount(PromptList, options)
    await flush()

    await button(wrapper, '编辑').trigger('click')
    await flush()
    await contentInput(wrapper).setValue('新的规则正文')
    await nextTick()

    const modal = wrapper.findComponent({ name: 'AModal' })
    modal.vm.$emit('ok')
    await flush()

    expect(promptsApi.update).toHaveBeenCalledWith('p1', expect.objectContaining({ expectedRevision: 1 }))
    expect(promptsApi.addVersion).toHaveBeenCalledWith('p1', {
      content: '新的规则正文',
      expectedRevision: 2
    })
  })

  it('元数据已保存而正文失败后，保留草稿并用最新修订号重试', async () => {
    let serverRevision = 1
    ;(promptsApi.update as any).mockImplementation((_id: string, request: { expectedRevision: number }) => {
      if (request.expectedRevision !== serverRevision) return Promise.reject(new Error('REVISION_CONFLICT'))
      return Promise.resolve({ ...detail, revision: ++serverRevision })
    })
    ;(promptsApi.addVersion as any).mockRejectedValueOnce(new Error('正文保存暂时失败'))
      .mockImplementationOnce((_id: string, request: { content: string; expectedRevision: number }) => {
        expect(request.expectedRevision).toBe(serverRevision)
        return Promise.resolve({ ...detail, revision: ++serverRevision,
          currentVersion: { ...detail.currentVersion, content: request.content } })
      })
    const wrapper = mount(PromptList, options)
    await flush()
    await button(wrapper, '编辑').trigger('click')
    await flush()
    await contentInput(wrapper).setValue('重试时保留的正文')
    const modal = wrapper.findComponent({ name: 'AModal' })
    modal.vm.$emit('ok')
    await flush()
    expect(modal.attributes('open')).toBe('true')
    expect((contentInput(wrapper).element as HTMLInputElement).value).toBe('重试时保留的正文')
    expect(serverRevision).toBe(2)

    modal.vm.$emit('ok')
    await flush()
    expect(promptsApi.update).toHaveBeenNthCalledWith(2, 'p1', expect.objectContaining({ expectedRevision: 2 }))
    expect(promptsApi.addVersion).toHaveBeenLastCalledWith('p1', {
      content: '重试时保留的正文', expectedRevision: 3
    })
    expect(serverRevision).toBe(4)
    expect(modal.attributes('open')).toBe('false')
    wrapper.unmount()
  })

  it('版本对比失败时清空结果并给出错误，不残留旧内容', async () => {
    ;(promptsApi.versions as any).mockResolvedValue([
      { id: 'v1', versionNo: 1, content: 'a', createdAt: '2026-10-20T10:00:00' },
      { id: 'v2', versionNo: 2, content: 'b', createdAt: '2026-10-20T10:00:00' }
    ])
    ;(promptsApi.diff as any).mockResolvedValueOnce([
      { type: 'add', text: 'x', oldLine: null, newLine: 1 }
    ])
    const wrapper = mount(PromptList, options)
    await flush()

    await button(wrapper, '版本').trigger('click')
    await flush()
    expect(wrapper.find('diff-viewer-stub').exists()).toBe(true)

    ;(promptsApi.diff as any).mockRejectedValueOnce(new Error('对比接口 500'))
    await button(wrapper, '对比').trigger('click')
    await flush()
    await nextTick()

    const alert = wrapper.find('a-alert-stub')
    expect(alert.exists()).toBe(true)
    expect(alert.attributes('message')).toContain('对比接口 500')
    expect(wrapper.find('diff-viewer-stub').exists()).toBe(false)
  })
})
