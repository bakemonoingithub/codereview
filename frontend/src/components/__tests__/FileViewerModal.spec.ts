// @vitest-environment happy-dom
import { describe, expect, it, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'

/**
 * 文件查看弹窗：两种模式共用一个壳。
 *
 * 断言口径说明：这里**不断言渲染后的 DOM 里的高亮/净化结果** —— happy-dom 下
 * DOMPurify 3.4.15 会剥掉允许的标签（C 批已记录的坑），拿它断言等于测环境。
 * 因此 `content` 模式断言的是**传给 MarkdownView 的 markdown 文本**（围栏是否带语言、
 * 内容是否原样在内），高亮本身由 `utils/markdown.spec.ts` 在纯字符串层面覆盖。
 */
vi.mock('@/api/project', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/project')>()
  return { ...actual, getFileContent: vi.fn() }
})

import FileViewerModal from '@/components/FileViewerModal.vue'
import { getFileContent } from '@/api/project'

const flush = () => new Promise((resolve) => setTimeout(resolve, 0))

function content(overrides: Record<string, unknown> = {}) {
  return {
    mode: 'content',
    path: 'src/A.java',
    ref: 'main',
    content: 'class A {}\n',
    truncated: false,
    totalLines: 1,
    ...overrides
  }
}

const options = {
  global: {
    stubs: {
      // 真实 a-modal 会 teleport 到 body，`wrapper.text()` 便是空的；这里用能内联渲染的替身，
      // 顺便让 `props('open')` 可断言（仓库里共享的 antStubs 不含 a-modal，故就地声明）
      'a-modal': {
        name: 'AModal',
        props: ['open', 'title', 'width', 'footer'],
        template: '<div class="modal-stub" v-if="open"><slot /></div>'
      },
      MarkdownView: { name: 'MarkdownView', props: ['text'], template: '<div class="md-stub">{{ text }}</div>' },
      // 布尔 prop 必须声明类型：模板里 `highlight`（裸属性）传进来的是空串，
      // 没有 type 声明就不会转成 true，断言会误判"组件没开高亮"（实测踩过）
      DiffViewer: {
        name: 'DiffViewer',
        props: {
          path: { type: String, default: '' },
          patch: { type: String, default: null },
          highlight: { type: Boolean, default: false },
          emptyText: { type: String, default: '' }
        },
        template: '<div class="diff-stub" />'
      },
      LoadErrorAlert: {
        name: 'LoadErrorAlert',
        props: ['message'],
        emits: ['retry'],
        template:
          '<div class="err-stub" v-if="message"><span class="err-msg">{{ message }}</span>' +
          '<button class="retry" @click="$emit(\'retry\')">重试</button></div>'
      }
    }
  }
}

function mountModal(props: Record<string, unknown> = {}) {
  return mount(FileViewerModal, {
    props: { open: true, projectId: '9', path: 'src/A.java', gitRef: 'main', ...props },
    ...options
  })
}

describe('FileViewerModal · 内容模式', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    ;(getFileContent as any).mockResolvedValue(content())
  })

  it('打开时按当前分支与路径请求内容', async () => {
    mountModal()
    await flush()

    expect(getFileContent).toHaveBeenCalledWith('9', {
      ref: 'main',
      path: 'src/A.java',
      mode: 'content',
      full: false
    })
  })

  it('把内容包成带语言围栏的 markdown 交给渲染器', async () => {
    const wrapper = mountModal()
    await flush()

    const md = wrapper.findComponent({ name: 'MarkdownView' })
    expect(md.props('text')).toBe('```java\nclass A {}\n```\n')
  })

  it('未截断时只说明总行数，且不出现「加载全文」', async () => {
    const wrapper = mountModal()
    await flush()

    expect(wrapper.text()).toContain('共 1 行')
    expect(wrapper.findAll('button').some((b) => b.text().includes('加载全文'))).toBe(false)
  })

  it('被截断时说明"已显示前 N 行，共 M 行"，并给「加载全文」', async () => {
    ;(getFileContent as any).mockResolvedValue(
      content({ content: 'a\nb\nc', truncated: true, totalLines: 5000 })
    )
    const wrapper = mountModal()
    await flush()

    expect(wrapper.text()).toContain('已显示前 3 行，共 5000 行')
    const button = wrapper.findAll('button').find((b) => b.text().includes('加载全文'))!
    expect(button).toBeTruthy()

    ;(getFileContent as any).mockResolvedValue(content({ content: 'a\nb', truncated: false, totalLines: 2 }))
    await button.trigger('click')
    await flush()

    expect(getFileContent).toHaveBeenLastCalledWith('9', expect.objectContaining({ full: true }))
  })

  it('加载失败时给出重试入口，而不是空白', async () => {
    ;(getFileContent as any).mockRejectedValue(new Error('未接入的仓库宿主：gitea.local'))
    const wrapper = mountModal()
    await flush()

    const alert = wrapper.find('.err-stub')
    expect(alert.exists()).toBe(true)
    expect(alert.text()).toContain('未接入的仓库宿主')

    ;(getFileContent as any).mockResolvedValue(content())
    await wrapper.find('button.retry').trigger('click')
    await flush()

    expect(wrapper.findComponent({ name: 'MarkdownView' }).exists()).toBe(true)
  })

  it('关闭时不请求；重新打开才请求', async () => {
    const wrapper = mountModal({ open: false })
    await flush()
    expect(getFileContent).not.toHaveBeenCalled()

    await wrapper.setProps({ open: true })
    await flush()
    expect(getFileContent).toHaveBeenCalledTimes(1)
  })
})

describe('FileViewerModal · 差异模式', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('用 sha 请求 diff，并把 patch 交给 DiffViewer（开语法高亮）', async () => {
    ;(getFileContent as any).mockResolvedValue(
      content({ mode: 'diff', ref: 'abcdef1234567890', content: '@@ -1 +1 @@\n-a\n+b', totalLines: 3 })
    )
    const wrapper = mountModal({ mode: 'diff', gitRef: 'abcdef1234567890' })
    await flush()

    expect(getFileContent).toHaveBeenCalledWith('9', {
      ref: 'abcdef1234567890',
      path: 'src/A.java',
      mode: 'diff',
      full: false
    })
    const diff = wrapper.findComponent({ name: 'DiffViewer' })
    expect(diff.props('patch')).toContain('@@ -1 +1 @@')
    expect(diff.props('highlight')).toBe(true)
    expect(diff.props('emptyText')).toContain('没有可显示的差异')
  })

  it('宿主没给 patch 时把 null 交给 DiffViewer，让它显示"无可显示差异"', async () => {
    ;(getFileContent as any).mockResolvedValue(content({ mode: 'diff', content: '', totalLines: 0 }))
    const wrapper = mountModal({ mode: 'diff', gitRef: 'abcdef1234567890' })
    await flush()

    expect(wrapper.findComponent({ name: 'DiffViewer' }).props('patch')).toBeNull()
    expect(wrapper.findComponent({ name: 'MarkdownView' }).exists()).toBe(false)
  })
})
