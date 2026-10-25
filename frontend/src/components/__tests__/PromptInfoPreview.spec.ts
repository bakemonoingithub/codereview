// @vitest-environment happy-dom
import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import PromptInfoPreview from '@/components/PromptInfoPreview.vue'
import { namedAntStubs } from '@/testUtils/antStubs'

describe('提示词版本预览', () => {
  it('明确传入版本后展示该正文，失效时不回退到当前版本', async () => {
    const current = { id: 'v2', versionNo: 2, content: '当前正文', createdAt: '2026-10-25' }
    const previous = { id: 'v1', versionNo: 1, content: '历史正文', createdAt: '2026-10-24' }
    const wrapper = mount(PromptInfoPreview, {
      props: { prompt: { id: 'p1', name: '规则', tags: [], currentVersion: current } as any, version: previous },
      global: { stubs: namedAntStubs }
    })
    expect(wrapper.text()).toContain('历史正文')
    expect(wrapper.text()).toContain('2026-10-24')
    expect(wrapper.text()).not.toContain('当前正文')
    await wrapper.setProps({ version: null })
    expect(wrapper.text()).toContain('所选提示词版本不可用')
    expect(wrapper.text()).not.toContain('当前正文')
    wrapper.unmount()
  })
})
