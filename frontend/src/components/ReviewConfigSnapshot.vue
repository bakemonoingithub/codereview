<template>
  <div class="config-snapshot">
    <!-- 只读提示：通栏红字，放在内容区最顶部，一眼可见 -->
    <div class="readonly-banner">
      <ExclamationCircleOutlined class="banner-icon" />
      <span>当前为历史审查记录快照，仅供查看，所有配置与审查按钮均已禁用；需要重审或标记 issue 请到「代码审查」页签操作。</span>
    </div>

    <a-descriptions size="small" :column="2" bordered class="meta">
      <a-descriptions-item label="记录 ID">{{ record.id }}</a-descriptions-item>
      <a-descriptions-item label="状态">
        <a-tag :color="recordStatusColor(record.status)">{{ recordStatusText(record.status) }}</a-tag>
      </a-descriptions-item>
      <a-descriptions-item label="分支">{{ record.branch || '—' }}</a-descriptions-item>
      <a-descriptions-item label="提交">{{ shortSha(record.commitSha) }}</a-descriptions-item>
      <a-descriptions-item label="策略">{{ strategyLabel(record.strategyId, strategyName) }}</a-descriptions-item>
      <a-descriptions-item label="创建时间">{{ formatTime(record.createdAt) }}</a-descriptions-item>
    </a-descriptions>

    <SplitPane left-title="审查配置与范围" right-title="审查结果" height="100%" storage-key="dsh:record-viewer:split">
      <template #left>
        <div class="select-row">
          <span class="select-label">分支</span>
          <a-select :value="record.branch" disabled style="width: 200px" placeholder="选择分支" />
        </div>

        <div class="select-row">
          <span class="select-label">策略</span>
          <a-select
            :value="record.strategyId"
            disabled
            style="width: 220px"
            placeholder="选择审查策略"
            :options="strategyOptions"
          />
        </div>
        <div class="select-row">
          <a-checkbox disabled>多文件合并审查</a-checkbox>
        </div>

        <!--
          审查范围：展示这条记录当时审了哪些文件。
          刻意**不显示复选框**（checkable=false）—— 这里是回看，不是选择；
          显示一排禁用勾选框反而会让人以为"勾选状态"有额外含义。
        -->
        <template v-if="scopePaths.length">
          <div class="tree-toolbar">
            <a-space>
              <a-button size="small" @click="expandAll">展开全部</a-button>
              <a-button size="small" @click="collapseAll">收起全部</a-button>
            </a-space>
            <a-button type="primary" size="small" disabled>开始审查</a-button>
          </div>
          <p class="scope-summary">审查范围：{{ scopePaths.length }} 个文件</p>
          <a-tree
            v-model:expanded-keys="expandedKeys"
            :tree-data="scopeTree"
            :checkable="false"
            :selectable="false"
          />
        </template>
        <a-empty v-else description="该记录未记录审查范围" />
      </template>

      <template #right>
        <a-spin :spinning="marksLoading" tip="加载审查结果…">
          <ReviewResult :record="record" :project-id="projectId" :marks="marks" readonly />
        </a-spin>
      </template>
    </SplitPane>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ExclamationCircleOutlined } from '@ant-design/icons-vue'
import type { IssueMark, ReviewRecord } from '@/api/review'
import {
  formatTime,
  parseResultJson,
  recordStatusColor,
  recordStatusText,
  scopePaths as collectScopePaths,
  shortSha,
  strategyLabel
} from '@/utils/reviewResult'
import { buildChangedFileTree } from '@/utils/changedFiles'
import ReviewResult from '@/components/ReviewResult.vue'
import SplitPane from '@/components/SplitPane.vue'

/**
 * 「审查记录-查看」的全屏只读视图。
 *
 * 复用「代码审查」页签的布局（SplitPane + 同一套结果渲染组件），
 * 区别只有一处：**没有任何写操作入口** ——
 * 下拉框/复选框/审查按钮全部 disabled，issue 标记按钮在 ReviewResult 里随 readonly 关闭。
 * 左栏刻意保留（而不是隐藏），因为负责人要求"看得出是同一套界面，只是被锁了"。
 *
 * 左栏另加**审查范围树**：回看这条记录当时审了哪些文件，且不可勾选。
 * 原先左栏只有一句"只读快照不加载文件树"，等于把记录里最该看的信息（范围）藏了起来。
 */
const props = withDefaults(
  defineProps<{
    record: ReviewRecord
    projectId: string
    marks?: IssueMark[]
    marksLoading?: boolean
    strategies?: { value: string; label: string }[]
  }>(),
  {
    marks: () => [],
    marksLoading: false,
    strategies: () => []
  }
)

const strategyOptions = computed(() =>
  props.strategies.map((s) => ({ value: s.value, label: s.label, disabled: true }))
)

const strategyName = computed(
  () => props.strategies.find((s) => s.value === props.record.strategyId)?.label || ''
)

/**
 * 审查范围：优先用持久化的 `scopeJson`；老记录没有它时回退到结果里的单元路径。
 * 这条兜底逻辑在 `utils/reviewResult.scopePaths` 里已有单测覆盖。
 */
const scopePaths = computed(() => collectScopePaths(props.record, parseResultJson(props.record.resultJson)))

/** 扁平路径 → 目录树（复用提交视图那套已经过测试的构建函数） */
const scopeTree = computed(() => buildChangedFileTree(scopePaths.value.map((path) => ({ path }))))

const expandedKeys = ref<string[]>([])

/** 收集所有目录节点的 key（叶子不参与展开） */
function collectDirKeys(nodes: any[]): string[] {
  const keys: string[] = []
  const walk = (list: any[]) => {
    for (const node of list) {
      if (node.children?.length) {
        keys.push(node.key)
        walk(node.children)
      }
    }
  }
  walk(nodes)
  return keys
}

function expandAll() {
  expandedKeys.value = collectDirKeys(scopeTree.value)
}

function collapseAll() {
  expandedKeys.value = []
}

// 默认全展开：这是快照，用户要的是一眼看到审了哪些文件，而不是逐层点开
watch(scopeTree, (nodes) => {
  expandedKeys.value = collectDirKeys(nodes)
}, { immediate: true })
</script>

<style scoped lang="less">
.config-snapshot {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
}
.readonly-banner {
  flex: none;
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
  padding: 8px 12px;
  color: #cf1322;
  background: #fff1f0;
  border: 1px solid #ffa39e;
  border-radius: 4px;
  font-weight: 600;
}
.banner-icon {
  color: #cf1322;
}
.meta {
  flex: none;
  margin-bottom: 12px;
}
.config-snapshot :deep(.split-wrap) {
  flex: 1;
  min-height: 0;
}
.select-row {
  display: flex;
  align-items: center;
  margin-bottom: 8px;
}
.select-label {
  flex: none;
  width: 60px;
  color: rgba(0, 0, 0, 0.88);
}
.tree-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}
.scope-summary {
  margin: 0 0 6px;
  color: #666;
  font-size: 12px;
}
.hint {
  color: #999;
  font-size: 12px;
}
</style>
