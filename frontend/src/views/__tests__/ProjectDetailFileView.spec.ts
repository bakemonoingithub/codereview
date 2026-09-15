// @vitest-environment happy-dom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'

/**
 * 结构视图里"点文件看内容"的入口。
 *
 * 三条必须守住的边界（都很容易写错）：
 * ① 只有**可查看的文本文件**能弹窗 —— 判定用后端下发的 `reviewable`，前端不自己看扩展名；
 * ② 点**目录**不弹窗（目录也有 path，但它该做的是展开/折叠）；
 * ③ 点标题**不会改变勾选集** —— 这棵树是 checkable 的，勾选是"审查范围"，
 *    点标题如果顺手改了它，用户会莫名多审/少审文件。
 */
vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { id: '9' } }),
  useRouter: () => ({ push: vi.fn() })
}))

vi.mock('@/api/review', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/review')>()
  return {
    ...actual,
    listReviews: vi.fn().mockResolvedValue({ records: [], total: 0, current: 1, size: 20 }),
    listMarks: vi.fn().mockResolvedValue([]),
    getReview: vi.fn(),
    retryReview: vi.fn()
  }
})

const { tree } = vi.hoisted(() => ({
  tree: [
    {
      path: 'src',
      name: 'src',
      type: 'tree',
      reviewable: false,
      children: [
        { path: 'src/A.java', name: 'A.java', type: 'blob', reviewable: true },
        { path: 'src/logo.png', name: 'logo.png', type: 'blob', reviewable: false }
      ]
    },
    { path: 'Makefile', name: 'Makefile', type: 'blob', reviewable: false }
  ]
}))

vi.mock('@/api/project', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/project')>()
  return {
    ...actual,
    getBranches: vi.fn().mockResolvedValue(['main']),
    getTree: vi.fn().mockResolvedValue(tree),
    listCommitPage: vi.fn().mockResolvedValue({ commits: [], hasMore: false }),
    getCommitDetail: vi.fn(),
    getAccuracy: vi.fn().mockResolvedValue([]),
    getProject: vi.fn().mockResolvedValue({ id: '9', name: '演示项目', giteaUrl: 'http://gitea/team/repo' }),
    getFileContent: vi.fn().mockResolvedValue({
      mode: 'content',
      path: 'src/A.java',
      ref: 'main',
      content: 'class A {}\n',
      truncated: false,
      totalLines: 1
    })
  }
})

vi.mock('@/api/strategy', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/strategy')>()
  return { ...actual, listStrategies: vi.fn().mockResolvedValue({ records: [] }) }
})

import ProjectDetail from '@/views/ProjectDetail.vue'
import { getFileContent } from '@/api/project'

/** 用内联渲染的 modal 替身，否则真实 a-modal 会 teleport，断言不到内容 */
const options = {
  global: {
    stubs: {
      AccuracyBar: true,
      ReportPanel: true,
      ReviewConfigSnapshot: true,
      ReviewResult: true,
      CommitTable: true,
      ChangedFileTree: true,
      ReviewRecordViewer: true,
      'a-modal': {
        name: 'AModal',
        props: ['open', 'title'],
        template: '<div class="modal-stub" v-if="open"><slot /></div>'
      }
    }
  }
}

const flush = () => new Promise((resolve) => setTimeout(resolve, 0))

async function mountView() {
  const wrapper = mount(ProjectDetail, options)
  await flush()
  await wrapper.vm.$nextTick()
  // 树默认收起，子节点不在 DOM 里；点「展开全部」再断言
  const expand = wrapper.findAll('button').find((b) => b.text().replace(/\s/g, '') === '展开全部')
  await expand!.trigger('click')
  await flush()
  return wrapper
}

function nodeByTitle(wrapper: any, title: string) {
  return wrapper.findAll('.ant-tree-node-content-wrapper').find((n: any) => n.text().includes(title))
}

describe('结构视图：点文件看内容', () => {
  beforeEach(() => {
    // 只清调用记录、不清实现（mockResolvedValue 由 vi.mock 工厂设置，不能被清掉）
    vi.clearAllMocks()
  })

  it('可查看的文件标题带可点样式', async () => {
    const wrapper = await mountView()

    const link = wrapper.findAll('.file-link').find((n) => n.text().includes('A.java'))
    expect(link, '可查看文件应带 file-link 样式').toBeTruthy()
  })

  it('不可查看的文件没有可点样式（无扩展名文件也在此列）', async () => {
    const wrapper = await mountView()

    expect(wrapper.findAll('.file-link').some((n) => n.text().includes('logo.png'))).toBe(false)
    expect(wrapper.findAll('.not-viewable').some((n) => n.text().includes('logo.png'))).toBe(true)
    expect(wrapper.findAll('.not-viewable').some((n) => n.text().includes('Makefile'))).toBe(true)
  })

  it('点可查看的文件会请求内容并打开弹窗', async () => {
    const wrapper = await mountView()

    const node = nodeByTitle(wrapper, 'A.java')!
    await node.trigger('click')
    await flush()

    expect(wrapper.find('.modal-stub').exists()).toBe(true)
    expect(getFileContent).toHaveBeenCalledWith('9', {
      ref: 'main',
      path: 'src/A.java',
      mode: 'content',
      full: false
    })
  })

  it('点目录不弹窗（目录该做的是展开/折叠）', async () => {
    const wrapper = await mountView()

    await nodeByTitle(wrapper, 'src')!.trigger('click')
    await flush()

    expect(wrapper.find('.modal-stub').exists()).toBe(false)
    expect(getFileContent).not.toHaveBeenCalled()
  })

  it('点不可查看的文件不弹窗', async () => {
    const wrapper = await mountView()

    await nodeByTitle(wrapper, 'logo.png')!.trigger('click')
    await flush()

    expect(wrapper.find('.modal-stub').exists()).toBe(false)
    expect(getFileContent).not.toHaveBeenCalled()
  })

  it('点文件标题不会改变勾选集（勾选是审查范围，不能被顺手改掉）', async () => {
    const wrapper = await mountView()
    const countBefore = wrapper.find('.count').text()

    await nodeByTitle(wrapper, 'A.java')!.trigger('click')
    await flush()

    expect(wrapper.find('.count').text()).toBe(countBefore)
    expect(countBefore).toContain('已选 0')
  })
})
