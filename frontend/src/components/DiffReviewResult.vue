<template>
  <div class="diff-review-result">
    <a-descriptions v-if="result.commit" size="small" :column="2" bordered class="mb8">
      <a-descriptions-item label="提交">{{ shortSha(result.commit.sha) }}</a-descriptions-item>
      <a-descriptions-item label="比较基线">
        {{ shortSha(result.commit.baseSha) }}{{ result.commit.merge ? '（merge 提交）' : '' }}
      </a-descriptions-item>
      <a-descriptions-item label="作者">{{ result.commit.author || '未知' }}</a-descriptions-item>
      <a-descriptions-item label="时间">{{ result.commit.date || '未知' }}</a-descriptions-item>
      <a-descriptions-item label="提交信息" :span="2">
        {{ result.commit.message || '（提交信息为空）' }}
      </a-descriptions-item>
      <a-descriptions-item label="统计" :span="2">
        {{ result.commit.files }} 个文件
        <span v-if="result.commit.additions != null">，+{{ result.commit.additions }}/-{{ result.commit.deletions }}</span>
        <a-tag v-if="result.commit.truncated" color="warning" class="ml8">文件列表可能不完整</a-tag>
      </a-descriptions-item>
    </a-descriptions>

    <a-alert v-if="result.error" type="error" show-icon :message="result.error" class="mb8" />

    <a-collapse v-if="units.length" v-model:active-key="activeKeys" accordion>
      <a-collapse-panel v-for="(unit, index) in units" :key="String(index)">
        <template #header>
          <a-space :size="6" wrap>
            <a-tag :color="unit.status === 'success' ? 'green' : 'red'">
              {{ unit.status === 'success' ? '成功' : '失败' }}
            </a-tag>
            <a-tag v-if="unit.changeType" color="geekblue">{{ unit.changeType }}</a-tag>
            <span class="unit-path">{{ unit.path }}</span>
            <span class="unit-name">{{ unit.unit?.name }}</span>
            <a-tag v-if="unit.intentVerdict" :color="verdictColor(unit.intentVerdict)">
              意图：{{ unit.intentVerdict }}
            </a-tag>
            <a-tag v-if="unit.truncated" color="orange">方法体已截断</a-tag>
            <a-tag v-if="unit.issues?.length" color="red">{{ unit.issues.length }} 个问题</a-tag>
          </a-space>
        </template>

        <div v-if="unit.status === 'failed'" class="error-text">{{ unit.error }}</div>
        <template v-else>
          <p v-if="unit.summary" class="summary">{{ unit.summary }}</p>
          <p v-if="unit.intentNote" class="intent-note">意图判断：{{ unit.intentNote }}</p>
          <p v-if="unit.note" class="note">{{ unit.note }}</p>

          <a-spin :spinning="loadingPatch[unit.path]">
            <DiffViewer
              :path="unit.path"
              :patch="patches[unit.path]"
              :status="unit.changeType"
              :comments="commentsOf(unit)"
              empty-text="该文件无可用 diff（二进制或改动过大）"
            >
              <template #extend="{ items }">
                <div v-for="(item, i) in items" :key="i" class="comment">
                  <div class="comment-head">
                    <a-tag :color="severityColor(item.issue.severity)">{{ item.issue.severity }}</a-tag>
                    <span class="comment-title">{{ item.issue.title }}</span>
                    <a-space :size="4" class="comment-actions">
                      <a-button
                        size="small"
                        :type="markOf(unit.path, item.issueIndex) === MARK_FALSE_POSITIVE ? 'primary' : 'default'"
                        danger
                        @click="$emit('mark', unit.path, item.issueIndex, MARK_FALSE_POSITIVE)"
                      >
                        误报
                      </a-button>
                      <a-button
                        size="small"
                        :type="markOf(unit.path, item.issueIndex) === MARK_ACCEPTED ? 'primary' : 'default'"
                        @click="$emit('mark', unit.path, item.issueIndex, MARK_ACCEPTED)"
                      >
                        已采纳
                      </a-button>
                      <a-button
                        v-if="markOf(unit.path, item.issueIndex)"
                        size="small"
                        type="text"
                        @click="$emit('mark', unit.path, item.issueIndex, MARK_NONE)"
                      >
                        撤销
                      </a-button>
                    </a-space>
                  </div>
                  <div v-if="item.issue.description" class="comment-body">{{ item.issue.description }}</div>
                  <div v-if="item.issue.suggestion" class="comment-suggestion">建议：{{ item.issue.suggestion }}</div>
                </div>
              </template>
            </DiffViewer>
          </a-spin>

          <a-table
            v-if="unlocatedIssues(unit).length"
            :data-source="unlocatedIssues(unit)"
            :pagination="false"
            size="small"
            row-key="title"
            class="mt8"
          >
            <a-table-column title="级别" data-index="severity" width="70" />
            <a-table-column title="问题" data-index="title" />
            <a-table-column title="建议" data-index="suggestion" />
          </a-table>
        </template>
      </a-collapse-panel>
    </a-collapse>

    <a-empty v-else description="该记录没有审查单元" />
  </div>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import DiffViewer from '@/components/DiffViewer.vue'
import { getFilePatch } from '@/api/project'
import { MARK_ACCEPTED, MARK_FALSE_POSITIVE, MARK_NONE, type IssueMark } from '@/api/review'

const props = defineProps<{
  projectId: string
  commitSha: string
  recordId: string
  result: any
  marks: IssueMark[]
}>()

defineEmits<{
  (e: 'mark', unitPath: string, issueIndex: number, markValue: number): void
}>()

const activeKeys = ref<string[]>([])
const patches = reactive<Record<string, string | null>>({})
const loadingPatch = reactive<Record<string, boolean>>({})

const units = computed<any[]>(() => (Array.isArray(props.result?.units) ? props.result.units : []))

function shortSha(sha?: string) {
  return sha ? sha.slice(0, 7) : '—'
}

function severityColor(severity?: string) {
  const s = (severity || '').toUpperCase()
  if (s === 'MAJOR' || s === 'ERROR') return 'red'
  if (s === 'MINOR' || s === 'WARNING') return 'orange'
  return 'blue'
}

function verdictColor(verdict?: string) {
  switch (verdict) {
    case '符合':
      return 'green'
    case '部分符合':
      return 'orange'
    case '不符':
      return 'red'
    default:
      return 'default'
  }
}

/** 能锚定到新文件行号的 issue → 走 diff 行内评论 */
function commentsOf(unit: any) {
  return (unit.issues || [])
    .map((issue: any, index: number) => ({
      line: issue.newLine ?? issue.line ?? null,
      data: { issue, issueIndex: index }
    }))
    .filter((item: any) => item.line !== null)
}

/** 无法锚定行号的 issue → 退化为普通表格 */
function unlocatedIssues(unit: any) {
  return (unit.issues || []).filter((issue: any) => (issue.newLine ?? issue.line ?? null) === null)
}

function markOf(unitPath: string, issueIndex: number): number {
  const hit = (props.marks || []).find((m) => m.unitPath === unitPath && m.issueIndex === issueIndex)
  return hit ? hit.markValue : MARK_NONE
}

async function ensurePatch(path: string) {
  if (path in patches || loadingPatch[path]) {
    return
  }
  loadingPatch[path] = true
  try {
    patches[path] = await getFilePatch(props.projectId, props.commitSha, path)
  } catch {
    patches[path] = null
  } finally {
    loadingPatch[path] = false
  }
}

// 展开哪个单元才拉哪个文件的 patch（清单与 patch 分离）
watch(
  activeKeys,
  (keys) => {
    for (const key of keys) {
      const unit = units.value[Number(key)]
      if (unit?.path) {
        ensurePatch(unit.path)
      }
    }
  },
  { immediate: true }
)

watch(
  () => props.result,
  () => {
    activeKeys.value = units.value.length ? ['0'] : []
  },
  { immediate: true }
)
</script>

<style scoped lang="less">
.diff-review-result {
  .mb8 {
    margin-bottom: 8px;
  }
  .mt8 {
    margin-top: 8px;
  }
  .ml8 {
    margin-left: 8px;
  }
  .unit-path {
    font-weight: 600;
  }
  .unit-name {
    color: #666;
  }
  .summary {
    color: #666;
  }
  .intent-note {
    color: #0958d9;
  }
  .note {
    color: #d46b08;
  }
  .error-text {
    color: #cf1322;
    white-space: pre-wrap;
  }
  .comment {
    padding: 4px 0;
  }
  .comment-head {
    display: flex;
    align-items: center;
    gap: 6px;
  }
  .comment-title {
    font-weight: 600;
  }
  .comment-actions {
    margin-left: auto;
  }
  .comment-body {
    color: #333;
    font-size: 12px;
  }
  .comment-suggestion {
    color: #389e0d;
    font-size: 12px;
  }
}
</style>
