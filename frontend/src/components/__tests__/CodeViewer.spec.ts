// @vitest-environment happy-dom
import { describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import CodeViewer from '@/components/CodeViewer.vue'

describe('CodeViewer 行号与范围高亮', () => {
  it('数字使用原始行号，只高亮给定范围并区分当前范围', () => {
    const wrapper = mount(CodeViewer, {
      props: {
        text: 'alpha\nbeta\ngamma\ndelta',
        startLine: 10,
        ranges: [{ startLine: 10, endLine: 11 }, { startLine: 13, endLine: 13 }],
        activeRange: { startLine: 10, endLine: 11 }
      }
    })

    expect(wrapper.findAll('.code-gutter > span').map((line) => line.text())).toEqual(['10', '11', '12', '13'])
    expect(wrapper.findAll('.code-gutter span.is-hl')).toHaveLength(3)
    expect(wrapper.findAll('.code-gutter span.is-cur')).toHaveLength(2)
    expect(wrapper.findAll('.code-gutter span.is-start')).toHaveLength(1)
    expect(wrapper.findAll('.range')).toHaveLength(2)
    expect(wrapper.findAll('.range.active')).toHaveLength(1)
    // 高亮层不能改变正文，复制和查找仍按原文。
    expect(wrapper.find('pre').text()).toBe('alpha\nbeta\ngamma\ndelta')
    wrapper.unmount()
  })

  it('没有范围时不渲染高亮层', () => {
    const wrapper = mount(CodeViewer, { props: { text: 'only', startLine: 42 } })
    expect(wrapper.findAll('.range')).toHaveLength(0)
    expect(wrapper.findAll('.code-gutter span.is-hl')).toHaveLength(0)
    expect(wrapper.find('.code-gutter').text()).toContain('42')
    wrapper.unmount()
  })

  it('scrollToLine 按原始行号换算，而不是视图内序号', () => {
    const wrapper = mount(CodeViewer, { props: { text: 'a\nb\nc', startLine: 10 } })
    const scroller = document.createElement('div')
    scroller.scrollTo = vi.fn()
    ;(wrapper.vm as unknown as {
      scrollToLine: (line: number, container: HTMLElement | null, instant?: boolean) => void
    }).scrollToLine(12, scroller, true)

    // 顶部内边距 12，行高 22，第 12 行是视图内的第 3 行。
    expect(scroller.scrollTo).toHaveBeenCalledWith({ top: 12 + 2 * 22, behavior: 'auto' })
    wrapper.unmount()
  })
})
