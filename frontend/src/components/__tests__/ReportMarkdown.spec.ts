// @vitest-environment happy-dom
import { describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import ReportMarkdown from '@/components/ReportMarkdown.vue'

async function render(text: string) {
  const wrapper = mount(ReportMarkdown, { props: { text, sourceCount: 1 } })
  await vi.waitFor(() => {
    expect(wrapper.html()).toContain('S1')
  }, { timeout: 3000 })
  return wrapper
}

describe('报告正文引用', () => {
  it('把 [S1] 渲染成可点击引用并发出来源序号', async () => {
    const wrapper = await render('# 结论\n\n支付重试需幂等 [S1]。')

    const citation = wrapper.find('.report-citation')
    expect(citation.exists()).toBe(true)
    expect(citation.text()).toBe('[S1]')
    await citation.trigger('click')
    expect(wrapper.emitted('citation')?.[0]).toEqual([1])
    wrapper.unmount()
  })

  it('不把代码块里的 [S1] 当成引用按钮', async () => {
    const wrapper = await render('```\n[S1]\n```\n\n正文 [S1]')

    expect(wrapper.findAll('.report-citation')).toHaveLength(1)
    wrapper.unmount()
  })
})
