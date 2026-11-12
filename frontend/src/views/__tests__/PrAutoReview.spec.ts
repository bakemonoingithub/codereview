// @vitest-environment happy-dom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import PrAutoReview from '@/views/PrAutoReview.vue'
import { attrsStub, inputStub, namedAntStubs } from '@/testUtils/antStubs'

const mockPush = vi.fn()
const mockRouteQuery: Record<string, string> = {}

vi.mock('vue-router', () => ({
  useRoute: () => ({ path: '/projects/auto-review', query: mockRouteQuery }),
  useRouter: () => ({ push: mockPush })
}))

vi.mock('@/api/v2', () => ({
  projectsApi: { list: vi.fn() },
  modelsApi: { list: vi.fn() },
  promptsApi: { list: vi.fn(), detail: vi.fn(), versions: vi.fn() },
  prAutoReviewApi: {
    getConfig: vi.fn(),
    updateConfig: vi.fn(),
    lookupReviewer: vi.fn(),
    listDeliveries: vi.fn(),
    listEvents: vi.fn(),
    deliveryDetail: vi.fn()
  }
}))

import { modelsApi, prAutoReviewApi, projectsApi, promptsApi } from '@/api/v2'

const options = {
  global: {
    stubs: {
      ...namedAntStubs,
      'a-drawer': { ...attrsStub('a-drawer-stub'), name: 'ADrawer' },
      'a-radio-group': { ...attrsStub('a-radio-group-stub'), name: 'ARadioGroup' },
      'a-radio-button': { ...attrsStub('a-radio-button-stub'), name: 'ARadioButton' },
      'a-input-search': { ...inputStub('a-input-search-stub'), name: 'AInputSearch' },
      'a-card': { ...attrsStub('a-card-stub'), name: 'ACard' },
      'a-table': false,
      'a-table-column': false
    }
  }
}

const flush = () => new Promise((resolve) => setTimeout(resolve, 0))

const githubProject = {
  id: 'p-github',
  name: 'GitHub 核心库',
  repoUrl: 'https://github.com/engineering/core-lib.git',
  currentBranch: 'main',
  credentialConfigured: true,
  revision: 1,
  createdAt: '2026-11-10T10:00:00',
  updatedAt: '2026-11-10T10:00:00'
}

const giteaProject = {
  id: 'p-gitea',
  name: 'Gitea 订单服务',
  repoUrl: 'http://192.168.1.50:3000/engineering/order-service.git',
  currentBranch: 'main',
  credentialConfigured: true,
  revision: 1,
  createdAt: '2026-11-10T10:00:00',
  updatedAt: '2026-11-10T10:00:00'
}

const baseConfig = {
  id: 'cfg-1',
  projectId: 'p-github',
  enabled: true,
  targetBranch: 'main',
  modelConfigId: 'm-1',
  promptId: null,
  promptVersionId: null,
  reviewBaseUrl: 'https://review.example.com',
  webhookSecretConfigured: true,
  reviewers: [{ id: 101, login: 'li.ming' }],
  botActorId: 901,
  botLogin: 'code-review-bot',
  repoId: 31,
  repoFullName: 'engineering/core-lib',
  createdAt: '2026-11-11T10:00:00',
  updatedAt: '2026-11-11T10:00:00'
}

const giteaDelivery = {
  id: 'del-gitea-1',
  hostType: 'gitea',
  hostInstanceKey: 'http://192.168.1.50:3000',
  deliveryId: 'gta-000013',
  firstReceivedAt: '2026-11-12T14:20:00',
  lastReceivedAt: '2026-11-12T14:21:00',
  receiveCount: 2,
  eventName: 'issue_comment',
  eventType: 'pull_request_comment',
  eventAction: 'created',
  repoId: 42,
  repoFullName: 'engineering/order-service',
  prNumber: 13,
  actorUserId: 101,
  actorLogin: 'li.ming',
  sourceCommentId: 3201,
  projectId: 'p-gitea',
  triggerKind: 'RECHECK',
  targetBranch: 'main',
  decisionState: 'CREATED',
  decidedAt: '2026-11-12T14:20:03',
  decisionCode: null,
  decisionMessage: null,
  businessKey: 'pr-auto-review:abc123',
  canonicalRecordId: null,
  reviewTaskId: '01TASKRECHECK0000000000001',
  reviewUrl: 'https://review.example.com/#/reviews/01TASKRECHECK0000000000001?from=/reviews',
  commentState: 'SENT',
  commentId: 4801,
  updatedAt: '2026-11-12T14:20:05'
}

const giteaDeliveryDetail = {
  ...giteaDelivery,
  payloadSnapshot: '{"action":"created","is_pull":true,"comment":{"id":3201,"body":"/recheck"}}',
  taskRequestSnapshot: '{"projectId":"p-gitea"}',
  sourceHead: '9da674bc34987d6acbec4f8521953315ecdb7d20',
  targetHead: 'f228b3da1c2ec0bf0cad8c6e3bb35b5bb7788b0b',
  mergeBase: 'f228b3da1c2ec0bf0cad8c6e3bb35b5bb7788b0b',
  textCount: 2,
  binaryCount: 1,
  unreadableCount: 0,
  commentActorId: 901,
  commentBody: '[查看提交 9da674b 的审查进度与结果](https://review.example.com/#/reviews/01TASKRECHECK0000000000001?from=/reviews)',
  commentAttempts: 1,
  commentLastAttemptAt: '2026-11-12T14:20:05',
  commentHttpStatus: 201,
  commentError: null,
  commentCheckedAt: null,
  revision: 2,
  leaseUntil: null,
  nextAttemptAt: null
}

beforeEach(() => {
  vi.clearAllMocks()
  for (const key of Object.keys(mockRouteQuery)) delete mockRouteQuery[key]
  vi.mocked(projectsApi.list).mockResolvedValue({
    items: [githubProject, giteaProject],
    nextCursor: null,
    hasMore: false
  })
  vi.mocked(modelsApi.list).mockResolvedValue({
    items: [
      {
        id: 'm-1',
        name: 'DeepSeek 审查模型',
        baseUrl: 'https://api.example.com',
        modelName: 'deepseek-chat',
        credentialConfigured: true,
        contextWindow: 64000,
        maxOutput: 8000,
        tokenizer: null,
        protocol: 'OPENAI_CHAT',
        validationStatus: 'PASSED',
        agentValidationStatus: 'PASSED',
        revision: 1,
        createdAt: '2026-11-01T00:00:00',
        updatedAt: '2026-11-01T00:00:00'
      }
    ],
    nextCursor: null,
    hasMore: false
  })
  vi.mocked(promptsApi.list).mockResolvedValue({
    items: [
      {
        id: 'pr-common',
        name: '通用安全规范',
        purpose: 'COMMON',
        tags: [],
        revision: 1,
        currentVersionNo: 2,
        updatedAt: '2026-11-01T00:00:00'
      }
    ],
    nextCursor: null,
    hasMore: false
  })
  vi.mocked(prAutoReviewApi.getConfig).mockResolvedValue(baseConfig)
  vi.mocked(prAutoReviewApi.listEvents).mockResolvedValue(['pull_request', 'pull_request_comment', 'push'])
  vi.mocked(prAutoReviewApi.listDeliveries).mockResolvedValue({
    items: [giteaDelivery],
    nextCursor: null,
    hasMore: false,
    total: 1
  })
  vi.mocked(prAutoReviewApi.deliveryDetail).mockResolvedValue(giteaDeliveryDetail)
})

describe('PrAutoReview 页面', () => {
  it('GitHub 与 Gitea 项目共用同一套配置视图，并按项目切换宿主标识与 Webhook 接收地址', async () => {
    const wrapper = mount(PrAutoReview, options)
    await flush()

    expect(prAutoReviewApi.getConfig).toHaveBeenCalledWith('p-github')
    expect(wrapper.text()).toContain('GitHub')
    expect(wrapper.text()).toContain('engineering/core-lib')
    expect(wrapper.text()).toContain('li.ming')

    const s = (wrapper.vm as any).$.setupState
    expect(s.webhookEndpointUrl).toBe('https://review.example.com/webhooks/github')

    // 切换到 Gitea 项目
    vi.mocked(prAutoReviewApi.getConfig).mockResolvedValueOnce({
      ...baseConfig,
      projectId: 'p-gitea',
      repoFullName: 'engineering/order-service'
    })
    s.selectedProjectId = 'p-gitea'
    s.onProjectChange()
    await flush()

    expect(prAutoReviewApi.getConfig).toHaveBeenCalledWith('p-gitea')
    expect(wrapper.text()).toContain('Gitea')
    expect(wrapper.text()).toContain('engineering/order-service')
    expect(s.webhookEndpointUrl).toBe('https://review.example.com/webhooks/gitea')
  })

  it('支持按登录名查询添加审核者、拦截重复与机器人账号、展示 404 错误并保存配置', async () => {
    const wrapper = mount(PrAutoReview, options)
    await flush()
    const s = (wrapper.vm as any).$.setupState

    // 1. 重复账号直接拦截
    s.reviewerInput = 'li.ming'
    expect(await s.addReviewer()).toBe(false)
    expect(wrapper.find('[role="alert"]').text()).toContain('已在名单中')
    expect(prAutoReviewApi.lookupReviewer).not.toHaveBeenCalled()

    // 2. 机器人账号直接拦截
    s.reviewerInput = 'code-review-bot'
    expect(await s.addReviewer()).toBe(false)
    expect(wrapper.find('[role="alert"]').text()).toContain('回写机器人账号不能触发复审')

    // 3. 宿主返回 404 时提示错误且名单不变
    vi.mocked(prAutoReviewApi.lookupReviewer).mockRejectedValueOnce(
      new Error('未找到此账号，或当前凭据无法查看')
    )
    s.reviewerInput = 'unknown.user'
    expect(await s.addReviewer()).toBe(false)
    expect(wrapper.find('[role="alert"]').text()).toContain('未找到此账号，或当前凭据无法查看')
    expect(s.form.reviewers).toHaveLength(1)

    // 4. 查询成功加入白名单并保存配置
    vi.mocked(prAutoReviewApi.lookupReviewer).mockResolvedValueOnce({
      id: 102,
      login: 'chen.yu',
      name: 'Chen Yu',
      avatarUrl: null
    })
    s.reviewerInput = 'chen.yu'
    expect(await s.addReviewer()).toBe(true)
    expect(s.form.reviewers).toEqual([
      { id: 101, login: 'li.ming' },
      { id: 102, login: 'chen.yu' }
    ])

    vi.mocked(prAutoReviewApi.updateConfig).mockResolvedValueOnce({
      ...baseConfig,
      targetBranch: 'release',
      reviewers: [
        { id: 101, login: 'li.ming' },
        { id: 102, login: 'chen.yu' }
      ]
    })
    s.form.targetBranch = 'release'
    await s.saveConfig()
    await flush()

    expect(prAutoReviewApi.updateConfig).toHaveBeenCalledWith(
      'p-github',
      expect.objectContaining({
        enabled: true,
        targetBranch: 'release',
        reviewers: [
          { id: 101, login: 'li.ming' },
          { id: 102, login: 'chen.yu' }
        ]
      })
    )
  })

  it('处理记录列表保留各宿主事件原值，支持按事件、审查决定、回写状态和关键字筛选', async () => {
    mockRouteQuery.tab = 'records'
    mockRouteQuery.projectId = 'p-gitea'
    const wrapper = mount(PrAutoReview, options)
    await flush()

    expect(wrapper.text()).toContain('gta-000013')
    expect(wrapper.text()).toContain('收到 2 次')
    expect(wrapper.text()).toContain('pull_request_comment')
    expect(wrapper.text()).toContain('created')
    expect(wrapper.text()).toContain('#13')
    expect(wrapper.text()).toContain('已创建任务')
    expect(wrapper.text()).toContain('01TASKRECHECK0000000000001')
    expect(wrapper.text()).toContain('已发送')
    expect(wrapper.text()).toContain('#4801')

    const s = (wrapper.vm as any).$.setupState
    expect(s.eventOptions.map((o: any) => o.value)).toEqual([
      'pull_request',
      'pull_request_comment',
      'push'
    ])

    // 筛选触发查询
    s.filterEvent = 'pull_request_comment'
    s.filterDecision = 'CREATED'
    s.filterCommentState = 'SENT'
    s.filterKeyword = '#13'
    await s.loadDeliveries()
    await flush()

    expect(prAutoReviewApi.listDeliveries).toHaveBeenLastCalledWith(
      'p-gitea',
      expect.objectContaining({
        eventName: 'pull_request_comment',
        decisionState: 'CREATED',
        commentState: 'SENT',
        q: '#13'
      })
    )
  })

  it('投递详情抽屉展示脱敏请求、审查决定与冻结版本、评论回执，并支持跳转现有审查详情页', async () => {
    mockRouteQuery.tab = 'records'
    mockRouteQuery.projectId = 'p-gitea'
    const wrapper = mount(PrAutoReview, options)
    await flush()

    const s = (wrapper.vm as any).$.setupState
    await s.openDetail('del-gitea-1')
    await flush()

    expect(prAutoReviewApi.deliveryDetail).toHaveBeenCalledWith('p-gitea', 'del-gitea-1')
    // 1. 接收请求页签
    expect(wrapper.text()).toContain('http://192.168.1.50:3000')
    expect(wrapper.text()).toContain('pull_request_comment')
    expect(wrapper.text()).toContain('/recheck')

    // 2. 审查决定页签
    s.detailTab = 'decision'
    await wrapper.vm.$nextTick()
    expect(wrapper.text()).toContain('评论复审 (/recheck)')
    expect(wrapper.text()).toContain('pr-auto-review:abc123')
    expect(wrapper.text()).toContain('9da674bc3498')
    expect(wrapper.text()).toContain('文本 2 · 二进制 1 · 不可读 0')

    // 点击审查链接跳转现有详情页
    s.openTask('01TASKRECHECK0000000000001')
    expect(mockPush).toHaveBeenCalledWith({
      path: '/reviews/01TASKRECHECK0000000000001',
      query: { from: '/projects/auto-review' }
    })

    // 3. 评论回写页签
    s.detailTab = 'write'
    await wrapper.vm.$nextTick()
    expect(wrapper.text()).toContain('201')
    expect(wrapper.text()).toContain('#4801')
    expect(wrapper.text()).toContain('查看提交 9da674b 的审查进度与结果')
  })
})
