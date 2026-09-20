// @vitest-environment happy-dom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { message } from 'ant-design-vue'
import PromptList from '@/views/PromptList.vue'
import { attrsStub, namedAntStubs } from '@/testUtils/antStubs'

/**
 * 提示词编辑的**回填**契约（backlog ⑥：`openEdit` 静默覆盖用户输入）。
 *
 * <p>回归背景：原先弹窗同步先开、正文置空，随后 `.then` **无条件**写回 ——
 * 用户在等待期间输入的正文会被无声覆写；且闭包不校验 `editingId`，
 * 慢响应会跨记录/跨"新建"串进另一个表单，保存即把正文写坏。
 * 全仓库此前没有 PromptList 的 spec，这个回归无人把关。
 */
vi.mock('@/api/prompt', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/prompt')>()
  return {
    ...actual,
    listPrompts: vi.fn(),
    getPrompt: vi.fn(),
    diffPrompt: vi.fn(),
    createPrompt: vi.fn(),
    updatePrompt: vi.fn(),
    updatePromptContent: vi.fn(),
    deletePrompt: vi.fn()
  }
})

import { listPrompts, getPrompt } from '@/api/prompt'

const row = { id: '7', name: '通用规则', description: '', tags: '[]', updatedAt: '2026-09-01' }

const options = {
  global: {
    stubs: {
      ...namedAntStubs,
      // 模态框必须 stub 成"渲染默认插槽"，否则真实 Modal 把表单 teleport 到 body，wrapper 里查不到输入框
      'a-modal': { ...attrsStub('a-modal-stub'), name: 'AModal' },
      'a-table': false,
      'a-table-column': false
    }
  }
}

const flush = () => new Promise((resolve) => setTimeout(resolve, 0))

beforeEach(() => {
  vi.clearAllMocks()
})

async function mountList() {
  ;(listPrompts as any).mockResolvedValue({ records: [row] })
  const wrapper = mount(PromptList, options)
  await flush()
  await wrapper.vm.$nextTick()
  return wrapper
}

async function clickButton(wrapper: any, label: string) {
  const btn = wrapper.findAll('a-button-stub').find((b: any) => b.text().includes(label))
  expect(btn, `应能找到「${label}」按钮`).toBeTruthy()
  await btn!.trigger('click')
  await wrapper.vm.$nextTick()
}

function content(wrapper: any) {
  return (wrapper.find('a-textarea-stub input').element as HTMLInputElement).value
}

/** 一个"手动控制何时返回"的详情请求：模拟慢响应 */
function slowDetail() {
  let resolveDetail: (value: unknown) => void = () => {}
  ;(getPrompt as any).mockReturnValue(
    new Promise((resolve) => {
      resolveDetail = resolve
    })
  )
  return (value: unknown) => resolveDetail(value)
}

describe('提示词编辑回填', () => {
  it('用户在加载期间输入的正文不会被慢响应覆盖', async () => {
    const resolveDetail = slowDetail()
    const wrapper = await mountList()

    await clickButton(wrapper, '编辑')
    await wrapper.find('a-textarea-stub input').setValue('用户刚敲进去的正文')
    resolveDetail({ currentContent: '服务端返回的正文' })
    await flush()
    await wrapper.vm.$nextTick()

    expect(content(wrapper)).toBe('用户刚敲进去的正文')
  })

  it('用户没动过正文时正常回填', async () => {
    ;(getPrompt as any).mockResolvedValue({ currentContent: '服务端正文' })
    const wrapper = await mountList()

    await clickButton(wrapper, '编辑')
    await flush()
    await wrapper.vm.$nextTick()

    expect(content(wrapper)).toBe('服务端正文')
  })

  it('慢响应回来时若已切到「新建」，不把旧记录的正文灌进新表单', async () => {
    const resolveDetail = slowDetail()
    const wrapper = await mountList()

    await clickButton(wrapper, '编辑')
    await clickButton(wrapper, '新建提示词')
    resolveDetail({ currentContent: '旧记录的正文' })
    await flush()
    await wrapper.vm.$nextTick()

    expect(content(wrapper)).toBe('')
  })

  it('详情加载失败时给出提示，正文保持为空而不是假装没填', async () => {
    const errorSpy = vi.spyOn(message, 'error').mockImplementation(() => ({}) as any)
    ;(getPrompt as any).mockRejectedValue(new Error('网络异常'))
    const wrapper = await mountList()

    await clickButton(wrapper, '编辑')
    await flush()
    await wrapper.vm.$nextTick()

    expect(errorSpy).toHaveBeenCalled()
    expect(content(wrapper)).toBe('')
  })

  it('加载完成后不再显示"正在加载"提示', async () => {
    ;(getPrompt as any).mockResolvedValue({ currentContent: '正文' })
    const wrapper = await mountList()

    await clickButton(wrapper, '编辑')
    await flush()
    await wrapper.vm.$nextTick()

    expect(wrapper.text()).not.toContain('正在加载当前正文')
  })
})
