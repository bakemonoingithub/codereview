// @vitest-environment happy-dom
import { describe, expect, it } from 'vitest'
import { defineComponent, h } from 'vue'
import { mount } from '@vue/test-utils'
import LaunchModalShell from '@/components/LaunchModalShell.vue'

const LAUNCH_MODAL_WIDTH = 'min(1080px, calc(100vw - 48px))'

const ModalStub = defineComponent({
  name: 'AModal',
  props: {
    open: { type: Boolean, default: false },
    title: { type: String, default: '' },
    footer: { default: undefined },
    width: { type: [String, Number], default: '' },
    maskClosable: { type: Boolean, default: true },
    wrapClassName: { type: String, default: '' }
  },
  emits: ['cancel'],
  setup(props, { slots }) {
    return () => h('div', {
      class: 'modal-stub',
      'data-open': String(props.open),
      'data-title': props.title,
      'data-footer': props.footer === null ? 'null' : String(props.footer),
      'data-width': String(props.width),
      'data-mask': String(props.maskClosable),
      'data-wrap': props.wrapClassName
    }, slots.default?.())
  }
})

function mountShell(props?: { open?: boolean; title?: string }) {
  return mount(LaunchModalShell, {
    props: { title: '发起专项审查', ...props },
    slots: {
      intro: '按文件独立分析',
      alerts: '<div class="alert-slot">请确认版本</div>',
      body: '<div class="body-slot">表单</div>',
      hint: '提交后在后台执行',
      footer: '<button class="cancel">取消</button><button class="primary">开始审查</button>'
    },
    global: { stubs: { 'a-modal': ModalStub } }
  })
}

describe('LaunchModalShell', () => {
  it('三个发起弹窗共用同一宽度、遮罩和页脚栏', () => {
    const wrapper = mountShell()
    const modal = wrapper.find('.modal-stub')

    expect(LAUNCH_MODAL_WIDTH).toBe('min(1080px, calc(100vw - 48px))')
    expect(modal.attributes('data-width')).toBe(LAUNCH_MODAL_WIDTH)
    expect(modal.attributes('data-mask')).toBe('false')
    expect(modal.attributes('data-footer')).toBe('null')
    expect(modal.attributes('data-wrap')).toBe('launch-modal')
    expect(modal.attributes('data-title')).toBe('发起专项审查')
    expect(wrapper.find('.launch-intro').text()).toBe('按文件独立分析')
    expect(wrapper.find('.alert-slot').text()).toBe('请确认版本')
    expect(wrapper.find('.launch-body .body-slot').text()).toBe('表单')
    const bar = wrapper.find('.submit-bar')
    expect(bar.find('.submit-hint').text()).toBe('提交后在后台执行')
    expect(bar.text().indexOf('取消')).toBeLessThan(bar.text().indexOf('开始审查'))
  })

  it('关闭时同时通知 v-model 与取消', async () => {
    const wrapper = mountShell({ open: true })
    wrapper.findComponent({ name: 'AModal' }).vm.$emit('cancel')
    await wrapper.vm.$nextTick()

    expect(wrapper.emitted('update:open')?.[0]).toEqual([false])
    expect(wrapper.emitted('cancel')).toHaveLength(1)
  })
})
