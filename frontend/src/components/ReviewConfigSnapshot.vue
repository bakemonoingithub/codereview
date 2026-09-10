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

    <SplitPane left-title="审查配置" right-title="审查结果" height="100%" storage-key="dsh:record-viewer:split">
      <template #left>
        <div class="select-row">
          <span class="select-label">分支</span>
          <a-select :value="record.branch" disabled style="width: 200px" placeholder="选择分支" />
        </div>

        <a-tabs v-model:active-key="activeView" type="card" size="small">
          <a-tab-pane key="structure" tab="结构视图">
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
            <div class="tree-toolbar">
              <a-space>
                <a-button size="small" disabled>展开全部</a-button>
                <a-button size="small" disabled>收起全部</a-button>
              </a-space>
              <a-button type="primary" size="small" disabled>开始审查</a-button>
            </div>
            <a-empty description="只读快照不加载文件树" />
          </a-tab-pane>

          <a-tab-pane key="commit" tab="提交视图">
            <div class="select-row">
              <span class="select-label">策略</span>
              <a-select disabled style="width: 220px" placeholder="选择 diff 审查策略" />
            </div>
            <div class="tree-toolbar">
              <span class="hint">先选提交，再勾选要审查的变更文件</span>
              <a-button type="primary" size="small" disabled>开始审查</a-button>
            </div>
            <a-empty description="只读快照不加载提交与变更文件" />
          </a-tab-pane>
        </a-tabs>
      </template>

      <template #right>
        <a-spin :spinning="marksLoading">
          <ReviewResult
            :record="record"
            :project-id="projectId"
            :marks="marks"
            readonly
          />
        </a-spin>
      </template>
    </SplitPane>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { ExclamationCircleOutlined } from '@ant-design/icons-vue'
import type { IssueMark, ReviewRecord } from '@/api/review'
import {
  formatTime,
  recordStatusColor,
  recordStatusText,
  shortSha,
  strategyLabel
} from '@/utils/reviewResult'
import ReviewResult from '@/components/ReviewResult.vue'
import SplitPane from '@/components/SplitPane.vue'

/**
 * 「审查记录-查看」的全屏只读视图。
 *
 * 复用「代码审查」页签的布局（SplitPane + 同一套结果渲染组件），
 * 区别只有一处：**没有任何写操作入口** ——
 * 下拉框/复选框/文件树/审查按钮全部 disabled，issue 标记按钮在 ReviewResult 里随 readonly 关闭。
 * 左栏刻意保留（而不是隐藏），因为负责人要求"看得出是同一套界面，只是被锁了"。
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

const activeView = ref('structure')

const strategyOptions = computed(() =>
  props.strategies.map((s) => ({ value: s.value, label: s.label, disabled: true }))
)

const strategyName = computed(
  () => props.strategies.find((s) => s.value === props.record.strategyId)?.label || ''
)
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
.hint {
  color: #999;
  font-size: 12px;
}
</style>
