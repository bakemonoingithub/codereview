// @vitest-environment happy-dom
import { describe, expect, it } from 'vitest'
import { h } from 'vue'
import { mount } from '@vue/test-utils'
import { FormItem } from 'ant-design-vue'
import LaunchFormSections from '@/components/LaunchFormSections.vue'

const titles = { goal: '审查目标', scope: '范围与版本', extra: '补充要求' }

describe('LaunchFormSections', () => {
  it('固定渲染三张同结构卡片，标题来自配置，内容来自插槽', () => {
    const wrapper = mount(LaunchFormSections, {
      props: { titles },
      slots: {
        goal: '<div class="goal-slot">目标字段</div>',
        scope: '<div class="scope-slot">范围字段</div>',
        extra: '<div class="extra-slot">补充字段</div>'
      }
    })

    const sections = wrapper.findAll('.launch-section')
    expect(sections).toHaveLength(3)
    expect(sections[0].text()).toContain('1. 审查目标')
    expect(sections[0].text()).toContain('目标字段')
    expect(sections[1].text()).toContain('2. 范围与版本')
    expect(sections[1].text()).toContain('范围字段')
    expect(sections[2].text()).toContain('3. 补充要求')
    expect(sections[2].text()).toContain('补充字段')
    expect(wrapper.find('form.launch-form').exists()).toBe(true)
  })

  it('必填星号来自 Ant Design required，可选字段不打星', () => {
    const wrapper = mount(LaunchFormSections, {
      props: { titles },
      slots: {
        goal: () => [
          h(FormItem, { label: '项目', required: true }, () => h('input')),
          h(FormItem, {}, {
            label: () => ['审查名称 ', h('span', { class: 'muted small' }, '（可选）')],
            default: () => h('input')
          })
        ]
      }
    })

    const labels = wrapper.findAll('.ant-form-item-label label')
    const project = labels.find((label) => label.text().includes('项目'))
    const name = labels.find((label) => label.text().includes('审查名称'))
    expect(name?.text()).toContain('（可选）')
    expect(project?.classes()).toContain('ant-form-item-required')
    expect(name?.classes()).not.toContain('ant-form-item-required')
    expect(wrapper.find('.required').exists()).toBe(false)
  })
})
