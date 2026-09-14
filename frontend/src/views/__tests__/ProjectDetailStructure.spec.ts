// @vitest-environment happy-dom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'

/**
 * 「结构视图」的搜索 / 计数 / 全选 / 清空 / 可审查拦截。
 *
 * 语义（与提交视图的 ChangedFileTree 对齐）：
 * - 搜索只影响**显示与全选范围**，不会动已选中的集合；
 * - 因此计数要说清"筛选内选了几个"以及"有几个已选被搜掉了"；
 * - 可审查性由**后端下发**（`reviewable`），前端不自己判扩展名；
 *   不可审查的文件同样可勾选，只是在点「开始审查」时弹窗告知会被跳过。
 *
 * 断言口径：树**不用 DOM 文本**判，改用 `a-tree` 的 `tree-data` / `expanded-keys` 两个 prop。
 * 原因是 antd Tree 的节点增删带过渡动画，happy-dom 里退场节点会一直留在 DOM 上，
 * 照 DOM 断言只会测出环境的锅。
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

// vi.mock 的工厂会被提升到文件顶部，里面的变量必须一起提升，否则读不到
const { tree } = vi.hoisted(() => ({
  tree: [
    {
      path: 'src',
      name: 'src',
      type: 'tree',
      children: [
        { path: 'src/a.ts', name: 'a.ts', type: 'blob', reviewable: true },
        { path: 'src/b.ts', name: 'b.ts', type: 'blob', reviewable: true }
      ]
    },
    { path: 'README.md', name: 'README.md', type: 'blob', reviewable: true },
    {
      path: 'docs',
      name: 'docs',
      type: 'tree',
      children: [{ path: 'docs/guide.md', name: 'guide.md', type: 'blob', reviewable: true }]
    },
    // 唯一的不可审查文件：可勾选，但提交时会被后端跳过
    {
      path: 'assets',
      name: 'assets',
      type: 'tree',
      children: [{ path: 'assets/logo.png', name: 'logo.png', type: 'blob', reviewable: false }]
    }
  ]
}))

vi.mock('@/api/project', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/project')>()
  return {
    ...actual,
    getBranches: vi.fn().mockResolvedValue(['master']),
    getTree: vi.fn().mockResolvedValue(tree),
    listCommitPage: vi.fn().mockResolvedValue({ commits: [], hasMore: false }),
    getCommitDetail: vi.fn(),
    getAccuracy: vi.fn().mockResolvedValue([]),
    getProject: vi.fn().mockResolvedValue({ id: '9', name: '演示项目', giteaUrl: 'http://gitea/team/repo' })
  }
})

vi.mock('@/api/strategy', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/strategy')>()
  return {
    ...actual,
    listStrategies: vi.fn().mockResolvedValue({ records: [{ id: '7', name: '结构审查', analyzerType: 2 }] })
  }
})

import ProjectDetail from '@/views/ProjectDetail.vue'

const options = {
  global: {
    stubs: {
      AccuracyBar: true,
      ReportPanel: true,
      ReviewConfigSnapshot: true,
      ReviewResult: true,
      // SplitPane **不能 stub**：结构视图就在它的插槽里，
      // stub 掉插槽内容整块消失，断言会以"找不到搜索框"的形式假失败
      CommitTable: true,
      ChangedFileTree: true,
      ReviewRecordViewer: true
    }
  }
}

type Wrapper = ReturnType<typeof mount>

async function flush() {
  await new Promise((resolve) => setTimeout(resolve, 0))
}

async function mountView() {
  const wrapper = mount(ProjectDetail, options)
  await flush()
  await wrapper.vm.$nextTick()
  return wrapper
}

function searchBox(wrapper: Wrapper) {
  const input = wrapper.findAll('input').find((i) => i.attributes('placeholder') === '按路径搜索')
  expect(input, '结构视图应有「按路径搜索」输入框').toBeTruthy()
  return input!
}

async function fillSearch(wrapper: Wrapper, keyword: string) {
  await searchBox(wrapper).setValue(keyword)
  await flush()
  await wrapper.vm.$nextTick()
}

/**
 * 按文案找按钮。**必须忽略空白**：antd Button 会在两个汉字之间自动插空格
 * （"全选" 在 DOM 里是 "全 选"），按原样比较会找不到。
 */
function button(wrapper: Wrapper, text: string) {
  const found = wrapper
    .findAll('button')
    .find((b) => b.text().replace(/\s/g, '') === text)
  expect(found, `应存在按钮「${text}」`).toBeTruthy()
  return found!
}

function countText(wrapper: Wrapper) {
  return wrapper.find('.count').text()
}

function structureTree(wrapper: Wrapper) {
  const treeComponent = wrapper.findAllComponents({ name: 'ATree' })[0]
  expect(treeComponent, '结构视图应渲染 a-tree').toBeTruthy()
  return treeComponent!.props('treeData') as any[]
}

function flattenKeys(nodes: any[]): string[] {
  return nodes.flatMap((node) => [node.key, ...(node.children?.length ? flattenKeys(node.children) : [])])
}

function treeKeys(wrapper: Wrapper) {
  return flattenKeys(structureTree(wrapper))
}

function currentExpandedKeys(wrapper: Wrapper) {
  const treeComponent = wrapper.findAllComponents({ name: 'ATree' })[0]
  return treeComponent!.props('expandedKeys') as string[]
}

/**
 * 弹窗文字去掉空白，便于断言"标签+数值"这对相邻内容。
 *
 * **必须读 `document.body`**：antd Modal 走 Teleport 挂到 body，
 * `wrapper.text()` 只看组件自身渲染的 DOM，读不到弹窗里的内容。
 */
function modalText() {
  return (document.body.textContent || '').replace(/\s/g, '')
}

afterEach(() => {
  // Teleport 到 body 的弹窗不随 wrapper 卸载，手动清掉，避免污染后续用例
  document.body.innerHTML = ''
})

describe('结构视图：搜索 / 计数 / 全选 / 清空', () => {
  it('默认展示全部文件，并给出「已选 0 / 共 N 个文件」', async () => {
    const wrapper = await mountView()

    expect(countText(wrapper)).toBe('已选 0 / 共 5 个文件')
    expect(treeKeys(wrapper)).toEqual([
      'src',
      'src/a.ts',
      'src/b.ts',
      'README.md',
      'docs',
      'docs/guide.md',
      'assets',
      'assets/logo.png'
    ])
    expect(button(wrapper, '全选').exists()).toBe(true)
  })

  it('搜索后只保留命中的分支，计数切到「匹配」口径', async () => {
    const wrapper = await mountView()

    await fillSearch(wrapper, 'src')

    expect(treeKeys(wrapper)).toEqual(['src', 'src/a.ts', 'src/b.ts'])
    expect(countText(wrapper)).toBe('已选 0 / 匹配 2')
    expect(button(wrapper, '全选筛选结果').exists()).toBe(true)
  })

  it('「全选筛选结果」只选筛选出来的文件，不动筛选外的', async () => {
    const wrapper = await mountView()

    await fillSearch(wrapper, 'src')
    await button(wrapper, '全选筛选结果').trigger('click')
    await flush()

    expect(countText(wrapper)).toBe('已选 2 / 匹配 2')

    // 清空筛选后可见：只选中了 src 下两个，README/docs/assets 未被选中
    await fillSearch(wrapper, '')
    expect(countText(wrapper)).toBe('已选 2 / 共 5 个文件')
  })

  it('已选被搜索藏起来时显式提示条数，不静默丢选择', async () => {
    const wrapper = await mountView()

    await button(wrapper, '全选').trigger('click')
    await flush()
    expect(countText(wrapper)).toBe('已选 5 / 共 5 个文件')

    await fillSearch(wrapper, 'src')

    expect(countText(wrapper)).toBe('已选 2 / 匹配 2（另有 3 个已选不在筛选中）')
  })

  it('点「清空」把选中集清干净', async () => {
    const wrapper = await mountView()

    await button(wrapper, '全选').trigger('click')
    await flush()
    expect(countText(wrapper)).toBe('已选 5 / 共 5 个文件')

    await button(wrapper, '清空').trigger('click')
    await flush()
    expect(countText(wrapper)).toBe('已选 0 / 共 5 个文件')
  })

  it('关键词无命中时给出「没有匹配的文件」而不是「该分支无文件」', async () => {
    const wrapper = await mountView()

    await fillSearch(wrapper, 'zzz-not-exist')

    expect(wrapper.text()).toContain('没有匹配的文件')
    expect(wrapper.text()).not.toContain('该分支无文件')
    expect(countText(wrapper)).toBe('已选 0 / 匹配 0')
  })

  it('搜索命中的目录自动展开，命中的文件当场可见', async () => {
    const wrapper = await mountView()

    await fillSearch(wrapper, 'guide')

    expect(treeKeys(wrapper)).toEqual(['docs', 'docs/guide.md'])
    expect(currentExpandedKeys(wrapper)).toContain('docs')
  })

  it('「展开全部」用的仍是完整文件树的目录键', async () => {
    const wrapper = await mountView()

    await fillSearch(wrapper, 'src')
    await button(wrapper, '展开全部').trigger('click')
    await flush()

    expect(currentExpandedKeys(wrapper)).toEqual(expect.arrayContaining(['src', 'docs']))
  })

  /**
   * 回归：antd Tree 的**勾选回写里会带目录键**，必须过滤掉再存。
   *
   * 真实浏览器里 `a-tree` 默认父子联动（`checkStrictly=false`）：当过滤后的树中某个目录的
   * 可见子节点被全部勾上时，它会把**该目录自身的 key** 也一并回写到 `checkedKeys`。
   * 原实现用 `v-model:checked-keys` 直接落库 —— 于是清空搜索、整棵树恢复后，
   * 那个目录键仍然在选中集里，一勾就等于勾上它**全部子文件**。
   *
   * 注意：这里**必须手工触发** antd 的这次回写。happy-dom 里真实渲染的 `a-tree`
   * 不会自己产生它，所以只点"全选筛选结果"的用例永远发现不了这个 bug。
   */
  it('回写里混入目录键时被过滤，否则清空搜索会变成全选', async () => {
    const wrapper = await mountView()
    await fillSearch(wrapper, 'src')

    const treeComponent = wrapper.findAllComponents({ name: 'ATree' })[0]!
    treeComponent.vm.$emit('check', ['src', 'src/a.ts', 'src/b.ts'], { halfCheckedKeys: [] })
    await flush()
    await wrapper.vm.$nextTick()

    const checkedKeys = treeComponent.props('checkedKeys') as string[]
    expect(checkedKeys).not.toContain('src')
    expect(checkedKeys.sort()).toEqual(['src/a.ts', 'src/b.ts'])
    expect(countText(wrapper)).toBe('已选 2 / 匹配 2')

    await fillSearch(wrapper, '')
    expect(countText(wrapper)).toBe('已选 2 / 共 5 个文件')
  })

  /**
   * 回归：**换关键词再勾选，不能覆盖上一次的选择**。
   *
   * a-tree 只认识当前 treeData 里的节点，过滤后回写的 keys 不含被搜掉的已选。
   * 原先把回写/全选结果整体覆盖到选中集上，于是"用 A 筛一遍勾几个、再用 B 勾几个"
   * 会丢掉 A 那批。
   */
  it('换关键词再勾选时，上一次的选择不会被覆盖', async () => {
    const wrapper = await mountView()

    await fillSearch(wrapper, 'src')
    await button(wrapper, '全选筛选结果').trigger('click')
    await flush()
    expect(countText(wrapper)).toBe('已选 2 / 匹配 2')

    await fillSearch(wrapper, 'guide')
    await button(wrapper, '全选筛选结果').trigger('click')
    await flush()
    expect(countText(wrapper)).toBe('已选 1 / 匹配 1（另有 2 个已选不在筛选中）')

    await fillSearch(wrapper, '')
    expect(countText(wrapper)).toBe('已选 3 / 共 5 个文件')
  })

  it('换关键词后 a-tree 的回写不会抹掉筛选外的已选', async () => {
    const wrapper = await mountView()
    const treeCmp = () => wrapper.findAllComponents({ name: 'ATree' })[0]!

    await fillSearch(wrapper, 'src')
    treeCmp().vm.$emit('check', ['src', 'src/a.ts', 'src/b.ts'], { halfCheckedKeys: [] })
    await flush()
    await wrapper.vm.$nextTick()
    expect((treeCmp().props('checkedKeys') as string[]).sort()).toEqual(['src/a.ts', 'src/b.ts'])

    await fillSearch(wrapper, 'guide')
    treeCmp().vm.$emit('check', ['docs', 'docs/guide.md'], { halfCheckedKeys: [] })
    await flush()
    await wrapper.vm.$nextTick()

    expect((treeCmp().props('checkedKeys') as string[]).sort()).toEqual([
      'docs/guide.md',
      'src/a.ts',
      'src/b.ts'
    ])
  })
})

describe('结构视图：可审查拦截（不在名单内的文件提交时会被跳过）', () => {
  it('「全选」把不可审查文件也选上（不静默剔除）', async () => {
    const wrapper = await mountView()

    await button(wrapper, '全选').trigger('click')
    await flush()

    // 5 个文件全被选中，其中 assets/logo.png 不可审查 —— 它不该被偷偷漏掉
    expect(countText(wrapper)).toBe('已选 5 / 共 5 个文件')
  })

  it('确认弹窗写明会跳过几个、并把预估单元数算成可审查的那部分', async () => {
    const wrapper = await mountView()

    await button(wrapper, '全选').trigger('click')
    await flush()
    await button(wrapper, '开始审查').trigger('click')
    await flush()
    await wrapper.vm.$nextTick()

    const text = modalText()
    expect(text).toContain('其中1个不可审查')
    expect(text).toContain('不在可审查名单内')
    // 预估单元数排除不可审查文件：5 个里只有 4 个会被真审
    expect(text).toContain('预估单元数4')
  })

  it('范围内全是不可审查文件时不打开确认弹窗（不产生注定失败的记录）', async () => {
    const wrapper = await mountView()

    wrapper
      .findAllComponents({ name: 'ATree' })[0]!
      .vm.$emit('check', ['assets/logo.png'], { halfCheckedKeys: [] })
    await flush()
    await wrapper.vm.$nextTick()

    await button(wrapper, '开始审查').trigger('click')
    await flush()
    await wrapper.vm.$nextTick()

    expect(modalText()).not.toContain('预估单元数')
  })
})
