import { defineComponent, h, type VNodeChild } from 'vue'

/**
 * 组件测试用的 AntDV stub：**保留插槽渲染**。
 *
 * `@vue/test-utils` 的 `shallow: true` 不渲染插槽内容，于是按钮文案、SplitPane 里的
 * 结果区全都取不到，也没法断言 disabled。这里用带插槽的轻量 stub 顶掉 AntDV，
 * 让"置灰"这种 DOM 级契约能被真正断言。
 */

/**
 * 渲染组件声明的**所有**插槽（含具名插槽）。
 *
 * 只渲染 `default` 会静默丢掉具名插槽的内容 —— 例如 `a-alert` 的 `#action` 里放着
 * "重试"按钮，漏掉它会让"错误态有没有重试入口"这类断言以"找不到按钮"失败，
 * 却看不出是 stub 的锅。
 */
function renderAllSlots(slots: Record<string, unknown>, props: unknown): VNodeChild[] {
  return Object.values(slots)
    .filter((slot): slot is (p?: unknown) => VNodeChild => typeof slot === 'function')
    .map((slot) => slot(props))
}

/**
 * 保留 `disabled` 属性落到根元素，便于断言置灰。
 *
 * 三个坑：
 * 1. 插槽必须**调用**再交给 `h`（`h(tag, {}, slots.default)` 传的是函数本身，什么都不会渲染，
 *    于是按钮文案在 DOM 里是空的，"按钮文案 + 置灰"类断言全部落空）；
 * 2. `disabled` 必须显式声明为 Boolean prop，否则 Vue 会按"未知属性"把 `false`
 *    渲染成字符串 `disabled="false"`（看起来像置灰了，其实没有）；
 * 3. 声明成 prop 之后，Vue 的**属性继承不会自动把它应用到根元素**，必须自己显式渲染，
 *    否则 `disabled` 在 DOM 里彻底消失。
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
        h(tag, { ...attrs, disabled: props.disabled || undefined }, renderAllSlots(slots as any, props))
    }
  })

/**
 * 不关心内容、只需要占位的第三方组件。
 *
 * **必须把 prop 透传成插槽 props**：`a-table-column` 的 `#default="{ text }"` 就靠它取值，
 * 不透传的话插槽一解构就抛 `Cannot destructure property 'text' of 'undefined'`，
 * 或者更隐蔽地渲染成空字符串 —— 于是"状态列文案对不对"这类断言测的是空气。
 */
export const silentStub = (tag: string) =>
  defineComponent({
    setup(props, { slots }) {
      return () => h(tag, {}, renderAllSlots(slots as any, props))
    }
  })

/**
 * 复选框 stub：**内部必须真的放一个 `<input type="checkbox">`**。
 *
 * antd 的表格行选择渲染的是 `a-checkbox`，测试里要像用户那样勾选就只能靠设置这个
 * input 的 `checked` 并派发 change；只渲染一个空标签的话，行选择相关的测试
 * 会以"找不到 checkbox"失败 —— 测不到东西，却看不出是 stub 的锅。
 */
export const checkboxStub = () =>
  defineComponent({
    props: {
      checked: { type: Boolean, default: false },
      indeterminate: { type: Boolean, default: false },
      disabled: { type: Boolean, default: false }
    },
    emits: ['change', 'update:checked'],
    setup(props, { emit, slots }) {
      return () =>
        h('a-checkbox-stub', { disabled: props.disabled || undefined }, [
          h('input', {
            type: 'checkbox',
            checked: props.checked,
            disabled: props.disabled,
            onChange: (event: Event) => {
              const native = event as MouseEvent
              const checked = (native.target as HTMLInputElement).checked
              emit('update:checked', checked)
              // 形状对齐 antd CheckboxChangeEvent：表格的行选择会读 nativeEvent.shiftKey
              // 做区间选择，少了这个字段直接抛 TypeError
              emit('change', {
                target: { checked },
                nativeEvent: { shiftKey: !!native.shiftKey }
              })
            }
          }),
          slots.default ? slots.default() : []
        ])
    }
  })

/**
 * 输入框 stub：**内部必须真的放一个 `<input>`**。
 *
 * 与复选框同理：测试要像用户那样输入就只能对真实 input 调 `setValue`；
 * 只渲染一个空标签的话，表单类断言会以"找不到 input"失败，看不出是 stub 的锅。
 * 同时保留 `placeholder`，便于断言"已配置，留空不修改"这类提示文案。
 */
export const inputStub = (tag: string) =>
  defineComponent({
    props: {
      value: { type: [String, Number], default: '' },
      disabled: { type: Boolean, default: false },
      placeholder: { type: String, default: '' }
    },
    emits: ['update:value', 'change', 'pressEnter'],
    setup(props, { emit, slots, attrs }) {
      return () =>
        h(
          tag,
          {
            ...attrs,
            disabled: props.disabled || undefined,
            placeholder: props.placeholder || undefined
          },
          [
            h('input', {
              value: props.value,
              disabled: props.disabled,
              placeholder: props.placeholder,
              onInput: (event: Event) => {
                const value = (event.target as HTMLInputElement).value
                emit('update:value', value)
                emit('change', event)
              },
              onKeydown: (event: KeyboardEvent) => {
                if (event.key === 'Enter') {
                  emit('pressEnter', event)
                }
              }
            }),
            slots.default ? slots.default() : []
          ]
        )
    }
  })

export const antStubs = {
  'a-button': attrsStub('a-button-stub'),
  'a-select': attrsStub('a-select-stub'),
  'a-select-option': attrsStub('a-select-option-stub'),
  'a-checkbox': checkboxStub(),
  'a-input': inputStub('a-input-stub'),
  'a-input-password': inputStub('a-input-password-stub'),
  'a-textarea': inputStub('a-textarea-stub'),
  'a-table': silentStub('a-table-stub'),
  'a-table-column': silentStub('a-table-column-stub'),
  'a-collapse': silentStub('a-collapse-stub'),
  'a-collapse-panel': silentStub('a-collapse-panel-stub'),
  'a-space': silentStub('a-space-stub'),
  'a-tag': silentStub('a-tag-stub'),
  'a-tooltip': attrsStub('a-tooltip-stub'),
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

/**
 * 给每个 stub 补上**组件名**（`ATable`、`AButton`…）。
 *
 * 没有名字时 `findAllComponents({ name: 'ATable' })` 一律找不到 —— 测试会以
 * "找不到组件 → 读 undefined 的 props"这种看不出根因的方式失败。
 */
export const namedAntStubs = Object.fromEntries(
  Object.entries(antStubs).map(([key, component]) => {
    const name = 'A' + key.slice(2).replace(/(^|-)(\w)/g, (_, __, c: string) => c.toUpperCase())
    return [key, { ...component, name }]
  })
)
