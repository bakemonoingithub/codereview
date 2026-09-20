// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { antStubs } from '@/testUtils/antStubs'

/**
 * 耦合图的两条回归契约：
 *
 * 1. **容器变化要重绘**。原先只监听 window resize，而这张图在 SplitPane 里：
 *    拖分隔条、切全屏都改了容器宽度但窗口尺寸不变，canvas 停在旧尺寸上
 *    （图被裁一半/缩在角落），只能刷新页面。现在用 ResizeObserver 盯容器。
 * 2. **标签短、悬停全**。后端 `label` 给的是短类名、`id` 是全限定名，
 *    短名会重名（不同包的 Utils），所以标签显示短名、tooltip 必须给全限定名；
 *    节点的 `name` 仍保持全限定名，因为边是用 FQCN 引用节点的，改名会断连线。
 */
const chartMock = {
  setOption: vi.fn(),
  resize: vi.fn(),
  dispose: vi.fn()
}

vi.mock('echarts', () => ({
  init: vi.fn(() => chartMock)
}))

import * as echarts from 'echarts'
import CouplingResult from '@/components/CouplingResult.vue'

const result = {
  nodes: [
    { id: 'com.acme.util.Utils', label: 'Utils', fanOut: 9, high: true, inCycle: false },
    { id: 'com.acme.other.Utils', label: 'Utils', fanOut: 1, high: false, inCycle: false },
    { id: 'com.acme.core.Engine', label: 'Engine', fanOut: 2, high: false, inCycle: true }
  ],
  edges: [
    { source: 'com.acme.core.Engine', target: 'com.acme.util.Utils' },
    { source: 'com.acme.util.Utils', target: 'com.acme.core.Engine' }
  ],
  highCoupling: ['com.acme.util.Utils'],
  cycles: [['com.acme.core.Engine', 'com.acme.util.Utils']],
  summary: '依赖图 3 节点 / 2 边'
}

/** 捕获组件自己 new 出来的 ResizeObserver，方便手动触发回调 */
let observers: Array<{ callback: () => void; observed: Element[]; disconnected: boolean }> = []

class FakeResizeObserver {
  callback: () => void
  observed: Element[] = []
  disconnected = false

  constructor(callback: () => void) {
    this.callback = callback
    observers.push(this as any)
  }

  observe(el: Element) {
    this.observed.push(el)
  }

  disconnect() {
    this.disconnected = true
  }

  unobserve() {}
}

const options = {
  global: {
    stubs: { ...antStubs }
  }
}

/**
 * 统一记账：用例之间必须卸载。
 * window resize 监听挂在同一个 happy-dom window 上，不卸载就会跨用例累积，
 * 而 chart mock 是模块级的同一份 —— 断言"resize 被调用几次"必然虚高。
 */
let wrappers: Array<ReturnType<typeof mount>> = []

function mountChart(props: any) {
  const wrapper = mount(CouplingResult, { props, ...options })
  wrappers.push(wrapper)
  return wrapper
}

function lastOption() {
  const calls = (chartMock.setOption as any).mock.calls
  return calls[calls.length - 1][0]
}

describe('CouplingResult 图表', () => {
  beforeEach(() => {
    observers = []
    vi.clearAllMocks()
    vi.stubGlobal('ResizeObserver', FakeResizeObserver)
  })

  afterEach(() => {
    wrappers.forEach((wrapper) => wrapper.unmount())
    wrappers = []
    vi.unstubAllGlobals()
  })

  it('节点 name 保持全限定名（边按 FQCN 引用），另存短名给标签用', () => {
    mountChart({ result })

    const data = lastOption().series[0].data
    expect(data[0].name).toBe('com.acme.util.Utils')
    expect(data[0].short).toBe('Utils')
    expect(lastOption().series[0].links[0]).toEqual({
      source: 'com.acme.core.Engine',
      target: 'com.acme.util.Utils'
    })
  })

  it('标签走短名，tooltip 走全限定名（重名类分得清）', () => {
    mountChart({ result })

    const option = lastOption()
    const label = option.series[0].label.formatter
    expect(label({ data: { name: 'com.acme.util.Utils', short: 'Utils' } })).toBe('Utils')

    const tooltip = option.tooltip.formatter
    expect(tooltip({ dataType: 'node', data: { name: 'com.acme.util.Utils', short: 'Utils' } })).toBe(
      'com.acme.util.Utils'
    )
    expect(
      tooltip({ dataType: 'edge', data: { source: 'com.acme.core.Engine', target: 'com.acme.util.Utils' } })
    ).toBe('com.acme.core.Engine → com.acme.util.Utils')
  })

  it('后端没给 label 时用全限定名兜底出短名', () => {
    mountChart({ result: { nodes: [{ id: 'com.acme.a.B', high: false }] } })

    expect(lastOption().series[0].data[0].short).toBe('B')
  })

  it('容器尺寸变化（拖分隔条/切全屏）触发 resize', () => {
    mountChart({ result })

    expect(observers).toHaveLength(1)
    expect(observers[0].observed).toHaveLength(1)
    expect(chartMock.resize).not.toHaveBeenCalled()

    observers[0].callback()

    expect(chartMock.resize).toHaveBeenCalledTimes(1)
  })

  it('窗口 resize 仍然可用', () => {
    mountChart({ result })

    window.dispatchEvent(new Event('resize'))

    expect(chartMock.resize).toHaveBeenCalledTimes(1)
  })

  it('卸载时断开 observer 并销毁实例，避免拖影与泄漏', () => {
    const wrapper = mountChart({ result })

    wrapper.unmount()

    expect(observers[0].disconnected).toBe(true)
    expect(chartMock.dispose).toHaveBeenCalled()
  })

  it('echarts.init 只调一次，后续数据变化复用同一实例', async () => {
    const wrapper = mountChart({ result })
    expect(echarts.init).toHaveBeenCalledTimes(1)

    await wrapper.setProps({ result: { ...result, nodes: [...result.nodes] } })

    expect(echarts.init).toHaveBeenCalledTimes(1)
    expect((chartMock.setOption as any).mock.calls.length).toBeGreaterThan(1)
  })
})

/**
 * T-03：模块级（包级）耦合。
 *
 * 两条硬契约：
 * 1. **表看模块、图看类**，且模块表在类级图**上方**（与后端 summary"模块在前、类级在后"一致）；
 * 2. 旧记录没有 `modules` 字段时**不渲染空表**，改为一行"重新审查即可获得"的提示 ——
 *    空表会被读成"这个项目没有跨模块依赖"，与事实相反。
 */
describe('CouplingResult 模块级', () => {
  // 这几条要断言真实表格的行内容，所以放开 a-table 的 stub（与 ReportPanel.spec 同一套做法）
  const realTableOptions = {
    global: {
      stubs: { ...antStubs, 'a-table': false, 'a-table-column': false }
    }
  }

  const multiPackage = {
    ...result,
    moduleSummary: '模块 3 个；跨模块依赖 3 对、共 24 处类级依赖；高耦合模块 1 个（web）；模块级循环 1 组（inventory ↔ order）',
    modules: [
      { name: 'web', classCount: 12, ca: 0, ce: 4, instability: 1, high: true },
      { name: 'order', classCount: 30, ca: 2, ce: 1, instability: 0.33, high: false }
    ],
    moduleEdges: [{ from: 'order', to: 'inventory', weight: 12 }],
    moduleCycles: [['inventory', 'order']],
    moduleMutualPairs: [['inventory', 'order']]
  }

  function mountWith(props: any, options = realTableOptions) {
    const wrapper = mount(CouplingResult, { props, ...options })
    wrappers.push(wrapper)
    return wrapper
  }

  beforeEach(() => {
    observers = []
    vi.clearAllMocks()
    vi.stubGlobal('ResizeObserver', FakeResizeObserver)
  })

  afterEach(() => {
    wrappers.forEach((wrapper) => wrapper.unmount())
    wrappers = []
    vi.unstubAllGlobals()
  })

  it('渲染模块表：模块名、类数、Ca/Ce、两位小数的不稳定度', () => {
    const wrapper = mountWith({ result: multiPackage })

    expect(wrapper.text()).toContain('模块耦合度（按包聚合，表看模块 / 图看类）')
    expect(wrapper.text()).toContain('模块 3 个；跨模块依赖 3 对、共 24 处类级依赖')
    expect(wrapper.text()).toContain('web')
    expect(wrapper.text()).toContain('order')
    expect(wrapper.text()).toContain('1.00')
    expect(wrapper.text()).toContain('0.33')
  })

  it('高耦合模块带标记，模块级循环按 2 元环渲染成双向箭头', () => {
    const wrapper = mountWith({ result: multiPackage })

    expect(wrapper.text()).toContain('高耦合')
    expect(wrapper.text()).toContain('inventory ↔ order')
  })

  it('模块级循环超过两个模块时画成闭环，不写成双向', () => {
    const wrapper = mountWith({
      result: { ...multiPackage, moduleCycles: [['billing', 'inventory', 'order']] }
    })

    expect(wrapper.text()).toContain('billing → inventory → order → billing')
  })

  it('模块表排在类级图上方，并注明依赖口径', () => {
    const wrapper = mountWith({ result: multiPackage })

    const html = wrapper.html()
    expect(html.indexOf('模块耦合度（按包聚合')).toBeLessThan(html.indexOf('class="graph"'))
    expect(wrapper.text()).toContain('依赖按 import 统计，不含继承 / 反射 / 同包引用')
    expect(wrapper.find('a-alert-stub').exists()).toBe(false)
  })

  it('旧记录（没有 modules）不渲染空表，只提示重新审查', () => {
    const wrapper = mountWith({ result })

    const alert = wrapper.find('a-alert-stub')
    expect(alert.exists()).toBe(true)
    expect(alert.attributes('message')).toContain('没有模块级数据')
    expect(alert.attributes('description')).toContain('重新运行一次耦合度审查')
    expect(wrapper.text()).not.toContain('模块耦合度（按包聚合')
  })

  it('空的 modules 数组同样按"没有模块级数据"处理', () => {
    const wrapper = mountWith({ result: { ...result, modules: [], moduleSummary: '无模块数据（未解析到任何类）' } })

    expect(wrapper.find('a-alert-stub').attributes('message')).toContain('没有模块级数据')
  })
})
