<template>
  <div>
    <a-space style="margin-bottom: 16px">
      <a-select v-model:value="branch" style="width: 220px" :loading="branchLoading" @change="loadTree">
        <a-select-option v-for="b in branches" :key="b" :value="b">{{ b }}</a-select-option>
      </a-select>
      <a-button type="primary" :loading="triggering" @click="onTrigger">开始审查</a-button>
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
          <div v-else-if="review.status < 2">
            <a-progress :percent="review.progress" :status="review.status === 3 ? 'exception' : 'active'" />
          </div>
          <div v-else-if="review.status === 3">
            <a-alert type="error" message="审查失败" />
          </div>
          <div v-else>
            <div v-for="(file, i) in parsedResults" :key="i" class="file-result">
              <a-typography-title :level="5">{{ file.path }}</a-typography-title>
              <p class="summary">{{ file.result?.summary }}</p>
              <a-table
                v-if="file.result?.issues?.length"
                :data-source="file.result.issues"
                row-key="title"
                size="small"
                :pagination="false"
              >
                <a-table-column title="级别" data-index="severity" width="80" />
                <a-table-column title="问题" data-index="title" />
                <a-table-column title="建议" data-index="suggestion" />
              </a-table>
            </div>
          </div>
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
import { triggerReview, getReview, type ReviewRecord } from '@/api/review'

const route = useRoute()
const projectId = route.params.id as string

const branches = ref<string[]>([])
const branch = ref('')
const branchLoading = ref(false)
const treeData = ref<any[]>([])
const checkedKeys = ref<string[]>([])
const triggering = ref(false)
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

async function onTrigger() {
  const scope = checkedKeys.value.filter((k) => fileSet.has(k))
  if (!scope.length) {
    message.warning('请先勾选要审查的文件')
    return
  }
  triggering.value = true
  try {
    const record = await triggerReview(projectId, { branch: branch.value, scope })
    review.value = record
    startPoll(record.id)
  } catch (e: any) {
    message.error(e?.message || '触发失败')
  } finally {
    triggering.value = false
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

const parsedResults = computed(() => {
  if (!review.value?.resultJson) return []
  try {
    return JSON.parse(review.value.resultJson)
  } catch {
    return []
  }
})

onMounted(loadBranches)
</script>

<style scoped lang="less">
.file-result {
  margin-bottom: 16px;
}
.summary {
  color: #666;
}
</style>
