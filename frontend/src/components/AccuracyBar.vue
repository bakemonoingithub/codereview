<template>
  <div class="accuracy-bar">
    <a-spin :spinning="loading">
      <a-empty v-if="!loading && !stats.length" description="暂无审查结果，无法统计准确率" />
      <a-row v-else :gutter="[8, 8]">
        <a-col v-for="stat in stats" :key="stat.analyzerType" :xs="24" :sm="12" :md="8" :lg="6">
          <a-card size="small" class="stat-card">
            <div class="head">
              <span class="name">{{ stat.analyzerName || analyzerName(stat.analyzerType) }}</span>
              <a-tag :color="stat.category === 'static' ? 'purple' : 'blue'">
                {{ categoryLabel(stat.category) }}
              </a-tag>
            </div>
            <div class="metrics">
              <div class="metric">
                <span class="label">整体准确率</span>
                <a-tag :color="levelColor(accuracyLevel(stat.overallAccuracy))">
                  {{ percent(stat.overallAccuracy, stat.totalIssues) }}
                </a-tag>
              </div>
              <div class="metric">
                <span class="label">已复核准确率</span>
                <span class="value">{{ percent(stat.reviewedAccuracy, stat.markedIssues) }}</span>
              </div>
              <div class="metric">
                <span class="label">复核覆盖率</span>
                <a-tag :color="levelColor(coverageLevel(stat.reviewCoverage))">
                  {{ percent(stat.reviewCoverage, stat.totalIssues) }}
                </a-tag>
              </div>
            </div>
            <div class="detail">
              共 {{ stat.totalIssues }} 条 · 已标记 {{ stat.markedIssues }} 条 · 误报
              {{ stat.falsePositives }} · 已采纳 {{ stat.accepted }}
            </div>
          </a-card>
        </a-col>
      </a-row>
    </a-spin>
  </div>
</template>

<script setup lang="ts">
import {
  accuracyLevel,
  analyzerName,
  categoryLabel,
  coverageLevel,
  levelColor,
  percent,
  type AccuracyStat
} from '@/utils/accuracy'

withDefaults(
  defineProps<{
    stats: AccuracyStat[]
    loading?: boolean
  }>(),
  { loading: false }
)
</script>

<style scoped lang="less">
.accuracy-bar {
  .stat-card {
    height: 100%;
  }
  .head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-bottom: 6px;
  }
  .name {
    font-weight: 600;
    font-size: 13px;
  }
  .metrics {
    display: flex;
    flex-direction: column;
    gap: 2px;
  }
  .metric {
    display: flex;
    align-items: center;
    justify-content: space-between;
    font-size: 12px;
  }
  .label {
    color: #666;
  }
  .value {
    font-weight: 600;
  }
  .detail {
    margin-top: 6px;
    color: #999;
    font-size: 11px;
  }
}
</style>
