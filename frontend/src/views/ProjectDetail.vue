<template>
  <div>
    <a-space style="margin-bottom: 16px">
      <a-select v-model:value="branch" style="width: 200px" :loading="branchLoading" @change="loadTree">
        <a-select-option v-for="b in branches" :key="b" :value="b">{{ b }}</a-select-option>
      </a-select>
      <a-select
        v-model:value="strategyId"
        style="width: 220px"
        placeholder="选择审查策略"
        :options="strategyOptions"
      />
      <a-button type="primary" :loading="triggering" :disabled="!strategyId" @click="onTrigger">
        开始审查
      </a-button>
    </a-space>

    <a-row :gutter="16">
      <a-col :span="10">
        <a-card title="文件树" size="small">
          <a-tree
            v-if="treeData.length"
            checkable
            :tree-data="treeData"
            v-model:checked-keys="checkedKeys"
            :default-expand-all="false"
          />
          <a-empty v-else description="加载中…" />
        </a-card>
      </a-col>
      <a-col :span="14">
        <a-card title="审查结果" size="small">
          <div v-if="!review">尚未触发审查</div>
          <template v-else>
            <div v-if="review.status < 2">
              <a-progress :percent="review.progress" status="active" />
            </div>
            <div v-else>
              <a-alert :type="statusAlert.type" :message="statusAlert.text" show-icon style="margin-bottom: 12px" />
              <a-space v-if="review.status === 3 || review.status === 4" style="margin-bottom: 12px">
                <a-button :loading="retrying" @click="onRetry">重审失败单元</a-button>
              </a-space>
              <p v-if="parsed.summary" class="summary">{{ parsed.summary }}</p>
              <a-collapse v-if="parsed.units?.length">
                <a-collapse-panel v-for="(u, i) in parsed.units" :key="i" :header="unitTitle(u)">
                  <a-space style="margin-bottom: 8px">
                    <a-tag :color="u.status === 'success' ? 'green' : 'red'">
                      {{ u.status === 'success' ? '成功' : '失败' }}
                    </a-tag>
                    <span class="unit-meta">{{ u.unit.kind }} · 行 {{ u.unit.lines }}</span>
                  </a-space>
                  <div v-if="u.status === 'failed'" class="error-text">{{ u.error }}</div>
                  <template v-else>
                    <p v-if="u.summary" class="summary">{{ u.summary }}</p>
                    <a-table
                      v-if="u.issues?.length"
                      :data-source="u.issues"
                      row-key="title"
                      size="small"
                      :pagination="false"
                    >
                      <a-table-column title="级别" data-index="severity" width="80" />
                      <a-table-column title="行" data-index="line" width="60" />
                      <a-table-column title="问题" data-index="title" />
                      <a-table-column title="建议" data-index="suggestion" />
                    </a-table>
                  </template>
                </a-collapse-panel>
              </a-collapse>
            </div>
          </template>
        </a-card>
      </a-col>
    </a-row>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { message } from 'ant-design-vue'
import { getBranches, getTree, type TreeNode } from '@/api/project'
import { triggerReview, getReview, retryReview, parseResult, type ReviewRecord } from '@/api/review'
import { listStrategies } from '@/api/strategy'

const route = useRoute()
const projectId = route.params.id as string

const branches = ref<string[]>([])
const branch = ref('')
const branchLoading = ref(false)
const treeData = ref<any[]>([])
const checkedKeys = ref<string[]>([])
const strategyId = ref('')
const strategyOptions = ref<{ value: string; label: string }[]>([])
const triggering = ref(false)
const retrying = ref(false)
const review = ref<ReviewRecord | null>(null)
let pollTimer: number | undefined

function toTreeNodes(nodes: TreeNode[]): any[] {
  return nodes.map((n) => ({
    title: n.name,
    key: n.path,
    children: n.children?.length ? toTreeNodes(n.children) : undefined
  }))
}

function flattenFiles(nodes: TreeNode[]): Set<string> {
  const set = new Set<string>()
  const walk = (list: TreeNode[]) => {
    for (const n of list) {
      if (n.type === 'blob') set.add(n.path)
      if (n.children) walk(n.children)
    }
  }
  walk(nodes)
  return set
}

let fileSet = new Set<string>()

async function loadBranches() {
  branchLoading.value = true
  try {
    branches.value = await getBranches(projectId)
    branch.value = branches.value[0] || ''
    if (branch.value) await loadTree()
  } finally {
    branchLoading.value = false
  }
}

async function loadTree() {
  const nodes = await getTree(projectId, branch.value)
  treeData.value = toTreeNodes(nodes)
  fileSet = flattenFiles(nodes)
}

async function loadStrategies() {
  try {
    const page = (await listStrategies({ pageNum: 1, pageSize: 100 })) as any
    strategyOptions.value = (page?.records || []).map((s: any) => ({ value: s.id, label: s.name }))
    if (strategyOptions.value.length === 1) strategyId.value = strategyOptions.value[0].value
  } catch {
    // 策略加载失败不阻塞
  }
}

async function onTrigger() {
  const scope = checkedKeys.value.filter((k) => fileSet.has(k))
  if (!scope.length) {
    message.warning('请先勾选要审查的文件')
    return
  }
  if (!strategyId.value) {
    message.warning('请选择审查策略（可到「策略」菜单新建）')
    return
  }
  triggering.value = true
  try {
    const record = await triggerReview(projectId, { branch: branch.value, strategyId: strategyId.value, scope })
    review.value = record
    startPoll(record.id)
  } catch (e: any) {
    message.error(e?.message || '触发失败')
  } finally {
    triggering.value = false
  }
}

async function onRetry() {
  if (!review.value) return
  retrying.value = true
  try {
    await retryReview(review.value.id)
    message.success('已提交重审')
    startPoll(review.value.id)
  } catch (e: any) {
    message.error(e?.message || '重审失败')
  } finally {
    retrying.value = false
  }
}

function startPoll(id: string) {
  if (pollTimer) window.clearInterval(pollTimer)
  pollTimer = window.setInterval(async () => {
    try {
      const r = await getReview(id)
      review.value = r
      if (r.status >= 2) window.clearInterval(pollTimer)
    } catch {
      window.clearInterval(pollTimer)
    }
  }, 2000)
}

const parsed = computed(() => parseResult(review.value?.resultJson))

const statusAlert = computed(() => {
  const s = review.value?.status
  if (s === 2) return { type: 'success', text: '审查成功' }
  if (s === 3) return { type: 'error', text: '审查失败' }
  if (s === 4) return { type: 'warning', text: '部分成功（部分单元失败，可重审）' }
  return { type: 'info', text: '' }
})

function unitTitle(u: any) {
  return `${u.path} · ${u.unit.name}`
}

onMounted(() => {
  loadBranches()
  loadStrategies()
})
</script>

<style scoped lang="less">
.summary {
  color: #666;
}
.unit-meta {
  color: #999;
}
.error-text {
  color: #cf1322;
  white-space: pre-wrap;
}
</style>
