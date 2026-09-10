import { defineComponent, h } from 'vue'

/**
 * 组件测试用的 AntDV stub：**保留插槽渲染**。
 *
 * `@vue/test-utils` 的 `shallow: true` 不渲染插槽内容，于是按钮文案、SplitPane 里的
 * 结果区全都取不到，也没法断言 disabled。这里用带插槽的轻量 stub 顶掉 AntDV，
 * 让"置灰"这种 DOM 级契约能被真正断言。
 */

/**
 * 保留 `disabled` 属性落到根元素，便于断言置灰。
 *
 * 两个坑：
 * 1. `disabled` 必须显式声明为 Boolean prop，否则 Vue 会按"未知属性"把 `false`
 *    渲染成字符串 `disabled="false"`（看起来像置灰了，其实没有）；
 * 2. 声明成 prop 之后，Vue 的**属性继承不会自动把它应用到根元素**（继承只覆盖
 *    `$attrs` 里的东西），必须自己显式渲染，否则 `disabled` 在 DOM 里彻底消失。
 */
export const attrsStub = (tag: string) =>
  defineComponent({
    props: {
      disabled: {
        type: Boolean,
        default: false
      }
    },
    setup(props, { slots, attrs }) {
      return () =>
        h(tag, { ...attrs, disabled: props.disabled || undefined }, slots.default ? slots.default() : [])
    }
  })

/** 不关心内容、只需要占位的第三方组件 */
export const silentStub = (tag: string) =>
  defineComponent({
    setup(_props, { slots }) {
      return () => h(tag, {}, slots.default ? slots.default() : [])
    }
  })

export const antStubs = {
  'a-button': attrsStub('a-button-stub'),
  'a-select': attrsStub('a-select-stub'),
  'a-select-option': attrsStub('a-select-option-stub'),
  'a-checkbox': attrsStub('a-checkbox-stub'),
  'a-input': attrsStub('a-input-stub'),
  'a-input-password': attrsStub('a-input-password-stub'),
  'a-textarea': attrsStub('a-textarea-stub'),
  'a-table': silentStub('a-table-stub'),
  'a-table-column': silentStub('a-table-column-stub'),
  'a-collapse': silentStub('a-collapse-stub'),
  'a-collapse-panel': silentStub('a-collapse-panel-stub'),
  'a-space': silentStub('a-space-stub'),
  'a-tag': silentStub('a-tag-stub'),
  'a-alert': attrsStub('a-alert-stub'),
  'a-progress': attrsStub('a-progress-stub'),
  'a-empty': attrsStub('a-empty-stub'),
  'a-spin': silentStub('a-spin-stub'),
  'a-tabs': silentStub('a-tabs-stub'),
  'a-tab-pane': silentStub('a-tab-pane-stub'),
  'a-descriptions': silentStub('a-descriptions-stub'),
  'a-descriptions-item': silentStub('a-descriptions-item-stub'),
  'a-divider': silentStub('a-divider-stub'),
  'a-form': silentStub('a-form-stub'),
  'a-form-item': silentStub('a-form-item-stub')
}
