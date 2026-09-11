// @vitest-environment happy-dom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import StrategyList from '@/views/StrategyList.vue'
import { attrsStub, namedAntStubs } from '@/testUtils/antStubs'

/**
 * api-review 策略的 token 三态（A 批 E4）。
 *
 * token 已改为只写不读，编辑时**拿不到明文**。因此这段 UI 逻辑承担一个真实风险：
 * 若它照旧把"表单里的空 token"下发，服务端就会把已存的 Sonar token 覆盖掉 ——
 * 而验收指标 2/3 正依赖那个 token。这里把这个契约钉住。
 */
vi.mock('@/api/strategy', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/strategy')>()
  return {
    ...actual,
    listStrategies: vi.fn(),
    createStrategy: vi.fn(),
    updateStrategy: vi.fn(),
    deleteStrategy: vi.fn()
  }
})

vi.mock('@/api/model', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/model')>()
  return { ...actual, listModels: vi.fn().mockResolvedValue({ records: [] }) }
})

vi.mock('@/api/prompt', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/prompt')>()
  return { ...actual, listPrompts: vi.fn().mockResolvedValue({ records: [] }) }
})

import { listStrategies, updateStrategy } from '@/api/strategy'

/** 服务端回传的 api-review 策略：参数里**没有** token，只有 hasToken 标记 */
const apiReviewRow = {
  id: '77',
  name: 'Sonar 审查',
  analyzerType: 4,
  hasToken: true,
  paramsJson: JSON.stringify({ apiUrl: 'http://sonar/api', resultUrl: 'http://sonar/dashboard' }),
  createdAt: '2026-09-10 08:00:00'
}

const options = {
  global: {
    stubs: {
      ...namedAntStubs,
      // 模态框必须 stub 成"渲染默认插槽"，否则真实 Modal 会把表单 teleport 到 body，
      // wrapper 里查不到输入框；且要命名，才能用 findComponent 触发 onOk
      'a-modal': { ...attrsStub('a-modal-stub'), name: 'AModal' },
      // 表格与列必须用真实组件：stub 的列不会把真实 record 传给插槽，
      // 点"编辑"会拿到 undefined
      'a-table': false,
      'a-table-column': false
    }
  }
}

const flush = () => new Promise((resolve) => setTimeout(resolve, 0))

/** 取**最近一次**调用的参数：mock 是模块级的、跨用例累积，读 calls[0] 会读到别的用例 */
function lastUpdateCall() {
  const calls = (updateStrategy as any).mock.calls
  return calls[calls.length - 1]
}

// 每个用例从干净的调用记录开始（clearAllMocks 保留实现，只清调用历史）
beforeEach(() => {
  vi.clearAllMocks()
})

async function mountAndOpenEdit(hasToken = true) {
  ;(listStrategies as any).mockResolvedValue({ records: [{ ...apiReviewRow, hasToken }] })
  const wrapper = mount(StrategyList, options)
  await flush()
  await wrapper.vm.$nextTick()

  const editBtn = wrapper.findAll('a-button-stub').find((b) => b.text() === '编辑')
  expect(editBtn, '应能点开编辑').toBeTruthy()
  await editBtn!.trigger('click')
  await wrapper.vm.$nextTick()
  await flush()
  return wrapper
}

function modal(wrapper: Awaited<ReturnType<typeof mountAndOpenEdit>>) {
  return wrapper.findComponent({ name: 'AModal' })
}

describe('策略编辑：token 三态', () => {
  it('已配置 token 时给出"留空不修改"提示，且输入框是空的（明文不回传）', async () => {
    const wrapper = await mountAndOpenEdit()
    expect(wrapper.html()).toContain('已配置，留空不修改')
    const tokenInput = wrapper.find('a-input-password-stub input')
    expect(tokenInput.exists()).toBe(true)
    expect((tokenInput.element as HTMLInputElement).value).toBe('')
  })

  it('不重填直接保存：不下发 token 键（由服务端沿用原值，避免静默抹掉）', async () => {
    const wrapper = await mountAndOpenEdit()

    await modal(wrapper).vm.$emit('ok')
    await flush()

    const [id, payload] = lastUpdateCall()
    expect(id).toBe('77')
    expect(payload.params).not.toHaveProperty('token')
    expect(payload.params).not.toHaveProperty('clearToken')
    // 其余参数照常提交，编辑功能不退化
    expect(payload.params.apiUrl).toBe('http://sonar/api')
    expect(payload.params.resultUrl).toBe('http://sonar/dashboard')
  })

  it('重填 token：下发新值', async () => {
    const wrapper = await mountAndOpenEdit()

    await wrapper.find('a-input-password-stub input').setValue('brand-new-token')
    // 交互后要等一次刷新，再触发保存 —— 否则读到的还是旧的表单状态
    await wrapper.vm.$nextTick()
    await flush()

    await modal(wrapper).vm.$emit('ok')
    await flush()

    const [, payload] = lastUpdateCall()
    expect(payload.params.token).toBe('brand-new-token')
    expect(payload.params).not.toHaveProperty('clearToken')
  })

  it('勾选"清除 token"：下发 clearToken 而不是空字符串', async () => {
    const wrapper = await mountAndOpenEdit()

    const checkbox = wrapper.find('input[type="checkbox"]')
    expect(checkbox.exists(), '应存在"清除 token"复选框').toBe(true)
    await checkbox.setValue(true)
    await wrapper.vm.$nextTick()
    await flush()

    await modal(wrapper).vm.$emit('ok')
    await flush()

    const [, payload] = lastUpdateCall()
    expect(payload.params.clearToken).toBe(true)
    expect(payload.params).not.toHaveProperty('token')
  })

  it('没有已配置 token 时不显示"清除 token"', async () => {
    const wrapper = await mountAndOpenEdit(false)

    expect(wrapper.find('input[type="checkbox"]').exists()).toBe(false)
    expect(wrapper.html()).toContain('只读 token（可选）')
  })
})
