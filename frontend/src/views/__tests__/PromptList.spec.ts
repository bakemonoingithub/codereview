// @vitest-environment happy-dom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { nextTick } from 'vue'
import { mount } from '@vue/test-utils'
import { message } from 'ant-design-vue'
import PromptList from '@/views/PromptList.vue'
import { attrsStub, namedAntStubs } from '@/testUtils/antStubs'

/**
 * 提示词管理的两处错误态回归：
 *
 * 1. **编辑回填不得覆盖用户输入**（backlog ⑥）：原先弹窗同步先开、正文置空，随后 `.then`
 *    **无条件**写回 —— 等待期间输入的正文被无声覆写；闭包不校验 `editingId`，慢响应会
 *    跨记录/跨"新建"串进另一个表单；请求失败还没有 catch。
 * 2. **版本对比失败不得伪装成"暂无对比内容"**（backlog ⑥）：`openVersions` 原先不清空
 *    上一次的 `versionOptions`，失败后残留旧提示词的版本 ⇒ 点"对比"会带着
 *    「新记录 id + 旧记录版本 id」发出跨提示词的请求；`loadDiff` 失败也没有 catch，
 *    于是"接口失败"被渲染成"暂无对比内容"，或把上一对版本的 diff 留在屏幕上冒充结果。
 *
 * 全仓库此前没有 PromptList 的 spec，这两个回归无人把关。
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

import { listPrompts, getPrompt, diffPrompt } from '@/api/prompt'

const row = { id: '7', name: '通用规则', description: '', tags: '[]', updatedAt: '2026-09-01' }
const rowA = { id: '7', name: 'A 规则', description: '', tags: '[]', updatedAt: '2026-09-01' }
const rowB = { id: '8', name: 'B 规则', description: '', tags: '[]', updatedAt: '2026-09-02' }

const options = {
  global: {
    stubs: {
      ...namedAntStubs,
      // 模态框必须 stub 成"渲染默认插槽"，否则真实 Modal 把表单 teleport 到 body，wrapper 里查不到输入框
      'a-modal': { ...attrsStub('a-modal-stub'), name: 'AModal' },
      'a-table': false,
      'a-table-column': false,
      // 必须 stub 掉 DiffViewer：@git-diff-view 内部用 canvas 量文本，
      // happy-dom 下 getContext('2d') 返回 null ⇒ 渲染真实 diff 会抛**未处理的 rejection**，
      // 污染下一个用例（表现为"上一个用例的 wrapper.vm 为 null"这类莫名其妙的报错）
      DiffViewer: true
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
  await nextTick()
  return wrapper
}

async function mountTwoRows() {
  ;(listPrompts as any).mockResolvedValue({ records: [rowA, rowB] })
  const wrapper = mount(PromptList, options)
  await flush()
  await nextTick()
  return wrapper
}

function button(wrapper: any, label: string) {
  return wrapper.findAll('a-button-stub').find((b: any) => b.text() === label)!
}

async function clickButton(wrapper: any, label: string) {
  const btn = button(wrapper, label)
  expect(btn, `应能找到「${label}」按钮`).toBeTruthy()
  await btn.trigger('click')
  await nextTick()
}

async function clickEdit(wrapper: any) {
  await button(wrapper, '编辑').trigger('click')
  await nextTick()
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

    await clickEdit(wrapper)
    await wrapper.find('a-textarea-stub input').setValue('用户刚敲进去的正文')
    resolveDetail({ currentContent: '服务端返回的正文' })
    await flush()
    await nextTick()

    expect(content(wrapper)).toBe('用户刚敲进去的正文')
  })

  it('用户没动过正文时正常回填', async () => {
    ;(getPrompt as any).mockResolvedValue({ currentContent: '服务端正文' })
    const wrapper = await mountList()

    await clickEdit(wrapper)
    await flush()
    await nextTick()

    expect(content(wrapper)).toBe('服务端正文')
  })

  it('慢响应回来时若已切到「新建」，不把旧记录的正文灌进新表单', async () => {
    const resolveDetail = slowDetail()
    const wrapper = await mountList()

    await clickEdit(wrapper)
    await clickButton(wrapper, '新建提示词')
    resolveDetail({ currentContent: '旧记录的正文' })
    await flush()
    await nextTick()

    expect(content(wrapper)).toBe('')
  })

  it('详情加载失败时给出提示，正文保持为空而不是假装没填', async () => {
    const errorSpy = vi.spyOn(message, 'error').mockImplementation(() => ({}) as any)
    ;(getPrompt as any).mockRejectedValue(new Error('网络异常'))
    const wrapper = await mountList()

    await clickEdit(wrapper)
    await flush()
    await nextTick()

    expect(errorSpy).toHaveBeenCalled()
    expect(content(wrapper)).toBe('')
  })

  it('加载完成后不再显示"正在加载"提示', async () => {
    ;(getPrompt as any).mockResolvedValue({ currentContent: '正文' })
    const wrapper = await mountList()

    await clickEdit(wrapper)
    await flush()
    await nextTick()

    expect(wrapper.text()).not.toContain('正在加载当前正文')
  })
})

// ---------------- 版本对比弹窗 ----------------

const versions = [
  { id: 'v1', versionNo: 1 },
  { id: 'v2', versionNo: 2 }
]

function versionButton(wrapper: any, index: number) {
  return wrapper.findAll('a-button-stub').filter((b: any) => b.text() === '版本')[index]
}

async function openVersionsOf(wrapper: any, index: number) {
  await versionButton(wrapper, index).trigger('click')
  await flush()
  await nextTick()
}

/** 版本弹窗里的错误提示（LoadErrorAlert → a-alert-stub，message 落在属性上） */
function versionError(wrapper: any) {
  const alert = wrapper.find('a-alert-stub')
  return alert.exists() ? String(alert.attributes('message')) : ''
}

describe('版本对比弹窗', () => {
  it('打开 B 的版本弹窗失败后，不会带着旧提示词的版本发出跨记录对比请求', async () => {
    ;(getPrompt as any).mockResolvedValueOnce({ versions })
    ;(diffPrompt as any).mockResolvedValue([])
    const wrapper = await mountTwoRows()

    await openVersionsOf(wrapper, 0)
    expect(diffPrompt).toHaveBeenCalledTimes(1)

    ;(getPrompt as any).mockRejectedValueOnce(new Error('boom'))
    await openVersionsOf(wrapper, 1)
    expect(versionError(wrapper)).toContain('boom')

    // 残留的旧选项会让"对比"带着「新记录 id + 旧记录版本 id」发请求 —— 必须没有第二次调用
    await button(wrapper, '对比').trigger('click')
    await flush()
    expect(diffPrompt).toHaveBeenCalledTimes(1)
  })

  it('对比失败时清空上一对版本的结果并给出错误', async () => {
    ;(getPrompt as any).mockResolvedValue({ versions })
    ;(diffPrompt as any).mockResolvedValueOnce([{ type: 'add', text: 'x', oldLine: null, newLine: 1 }])
    const wrapper = await mountTwoRows()

    await openVersionsOf(wrapper, 0)
    expect(wrapper.find('diff-viewer-stub').exists()).toBe(true)

    ;(diffPrompt as any).mockRejectedValueOnce(new Error('对比接口 500'))
    await button(wrapper, '对比').trigger('click')
    await flush()
    await nextTick()

    expect(versionError(wrapper)).toContain('对比接口 500')
    expect(wrapper.find('diff-viewer-stub').exists()).toBe(false)
    expect(wrapper.find('a-empty-stub').attributes('description')).toBe('暂无对比内容')
  })

  it('对比请求进行中按钮禁用，避免连点重复请求', async () => {
    ;(getPrompt as any).mockResolvedValue({ versions })
    let resolveDiff: (value: unknown) => void = () => {}
    ;(diffPrompt as any).mockReturnValue(
      new Promise((resolve) => {
        resolveDiff = resolve
      })
    )
    const wrapper = await mountTwoRows()

    await openVersionsOf(wrapper, 0)

    expect(button(wrapper, '对比').attributes('disabled')).toBeTruthy()

    // 收尾：把挂起的请求放掉，避免残留 pending promise 影响后续用例
    resolveDiff([])
    await flush()
  })
})
