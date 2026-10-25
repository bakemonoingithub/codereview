// @vitest-environment happy-dom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { h } from 'vue'
import ReviewCenter from '@/views/ReviewCenter.vue'
import { namedAntStubs } from '@/testUtils/antStubs'
const context = vi.hoisted(() => ({ push: vi.fn(), route: { query: {} as Record<string, string> } }))
vi.mock('vue-router', () => ({ useRoute: () => context.route, useRouter: () => ({ push: context.push }) }))
vi.mock('@/api/v2', () => ({ projectsApi: { list: vi.fn() }, tasksApi: { list: vi.fn() } }))
import { projectsApi, tasksApi } from '@/api/v2'
function deferred<T>() { let resolve!: (v: T) => void
  const promise = new Promise<T>((yes) => { resolve = yes }); return { promise, resolve }
}
const page = (id: string, pageNum = 1) => ({ items: [{ id }], nextCursor: null, hasMore: true, total: 65, pageNum, pageSize: 20 })
const state = (w: any) => w.vm.$.setupState as any
async function start(record?: { id: string; route: string }) { const w = mount(ReviewCenter, { global: { stubs: {
  ...namedAntStubs, AgentLaunch: true, ReviewLaunch: true,
  'a-table-column': {
    props: ['title'],
    setup(props: any, { slots }: any) {
      return () => h('span', props.title === '操作' && record ? slots.default?.({ record }) : [])
    }
  }
} } }); await flushPromises(); return w }
beforeEach(() => { sessionStorage.clear(); vi.resetAllMocks(); context.route.query = {}; vi.mocked(projectsApi.list).mockResolvedValue({ items: [] } as any)
  vi.mocked(tasksApi.list).mockResolvedValue(page('first') as any) })
describe('审查中心列表', () => {
  it('复制 Agent 参数时打开 Agent 发起表单，并传递原任务 ID', async () => {
    context.route.query = { launchReview: '1', copyFrom: 'previous' }
    const w = await start({ id: 'agent-task', route: 'AGENT' })
    const copyButton = w.findAll('a-button-stub').find((button) => button.text() === '复制参数')!
    await copyButton.trigger('click')
    expect(context.push).toHaveBeenCalledWith({ path: '/agents/new', query: { copyFrom: 'agent-task' } })
    w.unmount()
  })
  it('复制专项参数时打开专项发起弹窗，并传递原任务 ID', async () => {
    const w = await start({ id: 'specialist-task', route: 'SPECIALIST' })
    const copyButton = w.findAll('a-button-stub').find((button) => button.text() === '复制参数')!
    await copyButton.trigger('click')
    expect(context.push).toHaveBeenCalledWith({
      path: '/reviews', query: { launchReview: '1', copyFrom: 'specialist-task' }
    })
    w.unmount()
  })
  it('后续页按页码查询并替换，筛选后从第一页重载', async () => {
    const w = await start(); const s = state(w)
    vi.mocked(tasksApi.list).mockResolvedValue(page('second', 2) as any); await s.changePage(2, 20)
    expect(tasksApi.list).toHaveBeenLastCalledWith(expect.objectContaining({ pageNum: 2, pageSize: 20 }))
    expect(s.tasks.map((x: any) => x.id)).toEqual(['second'])
    s.filterType = 'ISSUE'; await s.load()
    expect(tasksApi.list).toHaveBeenLastCalledWith(expect.objectContaining({ pageNum: 1, type: 'ISSUE' }))
    w.unmount()
  })
  it('旧筛选迟到响应不能覆盖新筛选列表', async () => {
    const w = await start(); const s = state(w); const old = deferred<any>(); const latest = deferred<any>()
    vi.mocked(tasksApi.list).mockReturnValueOnce(old.promise).mockReturnValueOnce(latest.promise)
    s.filterType = 'ISSUE'; const first = s.load(); s.filterType = 'PATTERN'; const second = s.load()
    latest.resolve(page('latest')); await second; old.resolve(page('old')); await first
    expect(s.tasks[0].id).toBe('latest'); w.unmount()
  })
  it('从详情返回直接恢复当前页，不逐页追加请求', async () => {
    const w = await start(); const s = state(w)
    vi.mocked(tasksApi.list).mockResolvedValueOnce(page('second', 2) as any); await s.changePage(2, 20); w.unmount()
    vi.mocked(tasksApi.list).mockResolvedValueOnce(page('second', 2) as any)
    const restored = await start()
    expect(tasksApi.list).toHaveBeenLastCalledWith(expect.objectContaining({ pageNum: 2, pageSize: 20 }))
    expect(state(restored).tasks.map((row: any) => row.id)).toEqual(['second']); restored.unmount()
  })
  it('sessionStorage 不可用时仍然查询列表', async () => {
    const spy = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('不可用') })
    const w = await start(); expect(state(w).tasks[0].id).toBe('first'); w.unmount(); spy.mockRestore()
  })

  it('列表失败时显示带图标的错误条，重试是按钮而不是文字链接', async () => {
    vi.mocked(tasksApi.list).mockRejectedValueOnce(new Error('服务器内部错误'))
    const w = await start()
    const alert = w.find('.page-notices .load-error')
    expect(alert.exists()).toBe(true)
    expect(alert.attributes('type')).toBe('error')
    expect(alert.text()).toContain('服务器内部错误')
    expect(w.findAll('a-button-stub').some((b) => b.text() === '重试')).toBe(true)
    w.unmount()
  })

})
