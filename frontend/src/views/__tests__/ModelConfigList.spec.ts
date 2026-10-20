// @vitest-environment happy-dom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { nextTick } from 'vue'
import { mount } from '@vue/test-utils'
import { message } from 'ant-design-vue'
import ModelConfigList from '@/views/ModelConfigList.vue'
import { attrsStub, namedAntStubs } from '@/testUtils/antStubs'

vi.mock('@/api/v2', () => ({
  modelsApi: {
    list: vi.fn(),
    validate: vi.fn(),
    create: vi.fn(),
    update: vi.fn(),
    remove: vi.fn()
  }
}))

import { modelsApi } from '@/api/v2'

const row = {
  id: 'm1',
  name: '默认模型',
  baseUrl: 'https://gateway.local/v1',
  modelName: 'deepseek-chat',
  credentialConfigured: true,
  contextWindow: 32768,
  maxOutput: 4096,
  tokenizer: null,
  protocol: 'OPENAI_CHAT',
  validationStatus: 'NONE',
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
      'a-table-column': false
    }
  }
}

const flush = () => new Promise((resolve) => setTimeout(resolve, 0))

beforeEach(() => {
  vi.clearAllMocks()
  ;(modelsApi.list as any).mockResolvedValue({ items: [row], nextCursor: null, hasMore: false })
})

function verifyButton(wrapper: any) {
  return wrapper.findAll('a-button-stub').find((b: any) => b.text() === '验证')!
}

describe('ModelConfigList', () => {
  it('未验证与过期状态使用不同文案', async () => {
    const wrapper = mount(ModelConfigList, options)
    await flush()
    expect(wrapper.text()).toContain('未验证')

    ;(modelsApi.list as any).mockResolvedValue({
      items: [{ ...row, validationStatus: 'EXPIRED' }],
      nextCursor: null,
      hasMore: false
    })
    const second = mount(ModelConfigList, options)
    await flush()
    expect(second.text()).toContain('配置已变更，验证过期')
  })

  it('验证进行中禁用按钮，避免连点重复外呼', async () => {
    let resolveVerify: (value: unknown) => void = () => {}
    ;(modelsApi.validate as any).mockReturnValue(
      new Promise((resolve) => {
        resolveVerify = resolve
      })
    )
    const wrapper = mount(ModelConfigList, options)
    await flush()

    await verifyButton(wrapper).trigger('click')
    await nextTick()
    expect(verifyButton(wrapper).attributes('disabled')).toBeTruthy()
    await verifyButton(wrapper).trigger('click')
    expect(modelsApi.validate).toHaveBeenCalledTimes(1)

    resolveVerify({ result: 'PASSED', configRevision: 1, detail: 'ok' })
    await flush()
    await nextTick()
    expect(verifyButton(wrapper).attributes('disabled')).toBeUndefined()
  })

  it('验证失败时提示原因，不弹“验证完成”', async () => {
    const errorSpy = vi.spyOn(message, 'error').mockImplementation(() => ({}) as any)
    const successSpy = vi.spyOn(message, 'success').mockImplementation(() => ({}) as any)
    ;(modelsApi.validate as any).mockResolvedValue({
      result: 'FAILED',
      configRevision: 1,
      detail: 'connection refused'
    })
    const wrapper = mount(ModelConfigList, options)
    await flush()

    await verifyButton(wrapper).trigger('click')
    await flush()
    await nextTick()

    expect(errorSpy).toHaveBeenCalled()
    expect(String(errorSpy.mock.calls.at(-1)?.[0])).toContain('connection refused')
    expect(successSpy).not.toHaveBeenCalled()
  })

  it('编辑时凭据留空且不回显', async () => {
    const wrapper = mount(ModelConfigList, options)
    await flush()
    const edit = wrapper.findAll('a-button-stub').find((b) => b.text() === '编辑')!
    await edit.trigger('click')
    await flush()

    const password = wrapper.find('a-input-password-stub')
    expect(password.attributes('placeholder')).toContain('留空不修改')
    expect((password.find('input').element as HTMLInputElement).value).toBe('')
  })
})
