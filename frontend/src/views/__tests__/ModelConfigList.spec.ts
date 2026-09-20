// @vitest-environment happy-dom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { nextTick } from 'vue'
import { mount } from '@vue/test-utils'
import { message } from 'ant-design-vue'
import ModelConfigList from '@/views/ModelConfigList.vue'
import { attrsStub, namedAntStubs } from '@/testUtils/antStubs'

/**
 * 「验证」按钮的防连点与失败提示（backlog ⑥）。
 *
 * 回归背景：`verifyModel` 会真实外呼模型网关，而按钮既无 loading 也不禁用 ——
 * 等待期间可连点 N 次 = N 个并发外呼 + N 次状态写库；而且（后端当时无论成败都回 200）
 * 失败时先弹一条绿色"验证完成"，刷新后才看到红色"验证失败"，全程没有失败原因。
 * 后端已改为失败返回 3002 + 原因（见 ModelConfigService），这里锁住前端这一侧。
 */
vi.mock('@/api/model', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/model')>()
  return {
    ...actual,
    listModels: vi.fn(),
    verifyModel: vi.fn()
  }
})

import { listModels, verifyModel } from '@/api/model'

const row = { id: '5', name: 'deepseek', baseUrl: 'https://api.deepseek.com', modelName: 'deepseek-chat' }

const options = {
  global: {
    stubs: {
      ...namedAntStubs,
      'a-modal': { ...attrsStub('a-modal-stub'), name: 'AModal' },
      'a-table': false,
      'a-table-column': false
    }
  }
}

const flush = () => new Promise((resolve) => setTimeout(resolve, 0))

beforeEach(() => {
  vi.clearAllMocks()
  ;(listModels as any).mockResolvedValue({ records: [row] })
})

async function mountList() {
  const wrapper = mount(ModelConfigList, options)
  await flush()
  await nextTick()
  return wrapper
}

function verifyButton(wrapper: any) {
  return wrapper.findAll('a-button-stub').find((b: any) => b.text() === '验证')!
}

describe('模型配置的「验证」按钮', () => {
  it('验证进行中按钮禁用，避免连点重复外呼', async () => {
    let resolveVerify: (value: unknown) => void = () => {}
    ;(verifyModel as any).mockReturnValue(
      new Promise((resolve) => {
        resolveVerify = resolve
      })
    )
    const wrapper = await mountList()

    await verifyButton(wrapper).trigger('click')
    await nextTick()

    expect(verifyButton(wrapper).attributes('disabled')).toBeTruthy()
    await verifyButton(wrapper).trigger('click')
    expect(verifyModel).toHaveBeenCalledTimes(1)

    resolveVerify({})
    await flush()
    await nextTick()
    expect(verifyButton(wrapper).attributes('disabled')).toBeUndefined()
  })

  it('验证失败时提示原因，不弹"验证完成"', async () => {
    const errorSpy = vi.spyOn(message, 'error').mockImplementation(() => ({}) as any)
    const successSpy = vi.spyOn(message, 'success').mockImplementation(() => ({}) as any)
    ;(verifyModel as any).mockRejectedValue(new Error('连通验证失败：connection refused'))
    const wrapper = await mountList()

    await verifyButton(wrapper).trigger('click')
    await flush()
    await nextTick()

    expect(errorSpy).toHaveBeenCalled()
    expect(String(errorSpy.mock.calls.at(-1)?.[0])).toContain('connection refused')
    expect(successSpy).not.toHaveBeenCalled()
  })

  it('验证失败后按钮恢复可用（不留死锁）', async () => {
    vi.spyOn(message, 'error').mockImplementation(() => ({}) as any)
    ;(verifyModel as any).mockRejectedValue(new Error('boom'))
    const wrapper = await mountList()

    await verifyButton(wrapper).trigger('click')
    await flush()
    await nextTick()

    expect(verifyButton(wrapper).attributes('disabled')).toBeUndefined()
  })
})
