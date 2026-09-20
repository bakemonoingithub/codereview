// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, h, type VNodeChild } from 'vue'
import { mount } from '@vue/test-utils'
import { antStubs } from '@/testUtils/antStubs'

/**
 * 生成报告前的提示弹窗（T-02）。
 *
 * 背景：报告章节（概述/问题列表/修复方案/设计模式/模块耦合度）**全靠提示词一篇生成**，
 * 少勾了耦合度/设计模式/业务规则的记录，对应章节就只能由模型自由发挥；
 * 代码库不一致的记录混在一起，结论也会互相打架。
 *
 * 契约（负责人 2026-09-20 定）：
 * 1. **确认式**：点「生成报告」先弹提示，确认后才发请求，取消不发；
 * 2. 提示只做提醒，**不做校验**（本文件不测"拦下不合格勾选"，因为本来就不拦）；
 * 3. 「不再提示」勾选随确认写入 localStorage，跨会话有效，并可从卡片上重新开启。
 */
vi.mock('@/api/review', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/review')>()
  return { ...actual, listReviews: vi.fn(), getReview: vi.fn(), listMarks: vi.fn().mockResolvedValue([]) }
})
vi.mock('@/api/model', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/model')>()
  return { ...actual, listModels: vi.fn().mockResolvedValue({ records: [{ id: '1', name: '演示模型' }] }) }
})
vi.mock('@/api/prompt', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/prompt')>()
  return { ...actual, listPrompts: vi.fn().mockResolvedValue({ records: [] }) }
})
vi.mock('@/api/strategy', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/strategy')>()
  return { ...actual, listStrategies: vi.fn().mockResolvedValue({ records: [] }) }
})
vi.mock('@/api/report', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/report')>()
  return {
    ...actual,
    listReports: vi.fn().mockResolvedValue({ records: [] }),
    generateReport: vi.fn(),
    getReport: vi.fn()
  }
})

import { generateReport } from '@/api/report'
import ReportPanel from '@/components/ReportPanel.vue'

const TIP_KEY = 'report-generate-tip-dismissed'

/** 真实 a-modal 会 teleport 到 body，断言要绕到 document 上；这里用带确认/取消按钮的 stub 顶掉 */
const modalStub = defineComponent({
  props: {
    open: { type: Boolean, default: false },
    title: { type: String, default: '' },
    okText: { type: String, default: 'OK' },
    cancelText: { type: String, default: 'Cancel' }
  },
  emits: ['update:open', 'ok', 'cancel'],
  setup(props, { slots, emit }) {
    return () =>
      props.open
        ? h('div', { class: 'modal-stub' }, [
            h('div', { class: 'modal-title' }, props.title),
            h(
              'div',
              { class: 'modal-body' },
              Object.values(slots)
                .filter((s) => typeof s === 'function')
                .map((s) => (s as (p?: unknown) => VNodeChild)())
            ),
            h('button', { class: 'modal-ok', onClick: () => emit('ok') }, props.okText),
            h(
              'button',
              {
                class: 'modal-cancel',
                onClick: () => {
                  emit('update:open', false)
                  emit('cancel')
                }
              },
              props.cancelText
            )
          ])
        : null
  }
})

/** 真实 a-select 在这个用例里没法像用户那样选（要 teleport + 虚拟滚动），用原生 select 顶掉 */
const selectStub = defineComponent({
  props: {
    value: { type: [String, Number], default: undefined },
    options: { type: Array, default: () => [] },
    placeholder: { type: String, default: '' }
  },
  emits: ['update:value'],
  setup(props, { emit }) {
    return () =>
      h(
        'select',
        {
          class: 'select-stub',
          value: props.value,
          onChange: (e: any) => emit('update:value', e.target.value)
        },
        (props.options as any[]).map((o: any) => h('option', { value: o.value }, o.label))
      )
  }
})

const options = {
  global: {
    stubs: {
      ...antStubs,
      'a-table': false,
      'a-table-column': false,
      'a-modal': modalStub,
      'a-select': selectStub,
      ReviewRecordViewer: true
    }
  }
}

const flush = () => new Promise((resolve) => setTimeout(resolve, 0))

function row(index: number) {
  return {
    id: String(1000 + index),
    projectId: '9',
    strategyId: '5',
    strategyName: '变更审查（diff）',
    branch: 'master',
    commitSha: 'abcdef1234567890',
    status: 2,
    progress: 100,
    createdAt: '2026-09-10 08:00:00'
  }
}

let wrappers: Array<ReturnType<typeof mount>> = []

/** 挂载 + 选好模型与一条记录，让「生成报告」按钮可用 */
async function mountReady() {
  const { listReviews } = await import('@/api/review')
  ;(listReviews as any).mockResolvedValue({ records: [row(0)], total: 1, current: 1, size: 20 })

  const wrapper = mount(ReportPanel, { props: { projectId: '9' }, ...options })
  wrappers.push(wrapper)
  await flush()

  // 模型下拉是页面上第一个 select
  await wrapper.findAll('.select-stub')[0].setValue('1')
  await wrapper.findAll('.ant-table-tbody input[type="checkbox"]')[0].setValue(true)
  await flush()
  return wrapper
}

function generateButton(wrapper: ReturnType<typeof mount>) {
  return wrapper.findAll('a-button-stub').find((b) => b.text().replace(/\s/g, '') === '生成报告')!
}

/** 点弹窗里的按钮：stub 不接事件，只能通过触发 click 后由组件内部 @ok 处理，故这里直接调 vm 之外的方式 */
async function clickOk(wrapper: ReturnType<typeof mount>) {
  await wrapper.find('.modal-ok').trigger('click')
  await flush()
}

describe('ReportPanel 生成前提示（T-02）', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    window.localStorage.clear()
    wrappers = []
  })

  afterEach(() => {
    wrappers.forEach((w) => w.unmount())
    wrappers = []
    window.localStorage.clear()
  })

  it('点生成先弹提示、且不发请求；两点提醒都在文案里', async () => {
    const wrapper = await mountReady()

    await generateButton(wrapper).trigger('click')
    await flush()

    expect(wrapper.find('.modal-stub').exists()).toBe(true)
    expect(wrapper.text()).toContain('所选审查记录涉及的代码')
    expect(wrapper.text()).toContain('最好是一致的')
    expect(wrapper.text()).toContain('耦合度审查、设计模式审查、业务规则审查')
    expect(wrapper.text()).toContain('本次已选 1 条审查记录')
    expect(generateReport).not.toHaveBeenCalled()
  })

  it('确认后才真正提交', async () => {
    const wrapper = await mountReady()

    await generateButton(wrapper).trigger('click')
    await flush()
    await clickOk(wrapper)

    expect(generateReport).toHaveBeenCalledTimes(1)
    expect((generateReport as any).mock.calls[0][1].recordIds).toEqual(['1000'])
  })

  it('取消不发请求，弹窗关闭', async () => {
    const wrapper = await mountReady()

    await generateButton(wrapper).trigger('click')
    await flush()
    expect(wrapper.find('.modal-stub').exists()).toBe(true)

    await wrapper.find('.modal-cancel').trigger('click')
    await flush()

    expect(wrapper.find('.modal-stub').exists()).toBe(false)
    expect(generateReport).not.toHaveBeenCalled()
  })

  it('勾了「不再提示」并确认后，下一次生成直接提交（不再弹窗）', async () => {
    const wrapper = await mountReady()

    await generateButton(wrapper).trigger('click')
    await flush()
    await wrapper.find('.modal-body input[type="checkbox"]').setValue(true)
    await clickOk(wrapper)

    expect(window.localStorage.getItem(TIP_KEY)).toBe('1')
    expect(generateReport).toHaveBeenCalledTimes(1)

    await generateButton(wrapper).trigger('click')
    await flush()

    expect(wrapper.find('.modal-stub').exists()).toBe(false)
    expect(generateReport).toHaveBeenCalledTimes(2)
  })

  it('偏好跨会话有效：重新挂载后仍然直接生成', async () => {
    window.localStorage.setItem(TIP_KEY, '1')
    const wrapper = await mountReady()

    expect(wrapper.text()).toContain('重新开启生成前提示')

    await generateButton(wrapper).trigger('click')
    await flush()

    expect(wrapper.find('.modal-stub').exists()).toBe(false)
    expect(generateReport).toHaveBeenCalledTimes(1)
  })

  it('「重新开启生成前提示」之后会再弹一次', async () => {
    window.localStorage.setItem(TIP_KEY, '1')
    const wrapper = await mountReady()

    const restore = wrapper.findAll('a-button-stub').find((b) => b.text().replace(/\s/g, '') === '重新开启生成前提示')!
    await restore.trigger('click')
    await flush()

    expect(window.localStorage.getItem(TIP_KEY)).toBeNull()

    await generateButton(wrapper).trigger('click')
    await flush()

    expect(wrapper.find('.modal-stub').exists()).toBe(true)
    expect(generateReport).not.toHaveBeenCalled()
  })
})
