<template>
  <section class="strategy-association-info">
    <div class="info-heading">策略关联配置</div>
    <a-spin :spinning="loading">
      <a-alert v-if="error" type="error" show-icon :message="error" />
      <div class="info-grid">
        <div class="info-section">
          <div class="section-heading">模型</div>
          <template v-if="model">
            <strong class="item-title">{{ model.name }} · {{ model.modelName }}</strong>
            <div class="facts">
              <span>接口 <b :title="model.baseUrl">{{ model.baseUrl }}</b></span>
              <span>协议 <b>{{ model.protocol }}</b></span>
              <span>上下文 <b>{{ formatNumber(model.contextWindow) }}</b></span>
              <span>最大输出 <b>{{ formatNumber(model.maxOutput) }}</b></span>
              <span>凭据 <b>{{ model.credentialConfigured ? '已配置' : '未配置' }}</b></span>
            </div>
            <div class="badges">
              <StatusBadge :tone="validationTone(model.validationStatus)">
                专项审查 {{ validationLabel(model.validationStatus) }}
              </StatusBadge>
              <StatusBadge :tone="validationTone(model.agentValidationStatus)">
                Agent {{ validationLabel(model.agentValidationStatus) }}
              </StatusBadge>
            </div>
          </template>
          <span v-else class="muted">{{ modelSelected ? '模型详情暂不可用' : '未关联模型' }}</span>
        </div>

        <PromptInfoPreview
          :prompt="prompt"
          title="通用规则提示词"
          :selected="promptSelected"
          :loading="promptLoading"
        />
      </div>
    </a-spin>
  </section>
</template>

<script setup lang="ts">
import { toRefs } from 'vue'
import type { ModelView, PromptView } from '@/api/v2'
import StatusBadge from '@/components/StatusBadge.vue'
import PromptInfoPreview from '@/components/PromptInfoPreview.vue'

const props = withDefaults(defineProps<{
  model: ModelView | null
  prompt: PromptView | null
  modelSelected: boolean
  promptSelected: boolean
  loading?: boolean
  promptLoading?: boolean
  error?: string
}>(), {
  loading: false,
  promptLoading: false,
  error: ''
})
const { model, prompt, modelSelected, promptSelected, loading, promptLoading, error } = toRefs(props)

const validationLabels: Record<string, string> = {
  PASSED: '验证通过',
  FAILED: '验证失败',
  EXPIRED: '配置已变更，验证过期',
  NONE: '未验证'
}

function validationLabel(status: string): string {
  return validationLabels[status] || status
}

function validationTone(status: string): 'green' | 'amber' | 'red' | 'default' {
  if (status === 'PASSED') return 'green'
  if (status === 'FAILED') return 'red'
  if (status === 'EXPIRED' || status === 'NONE') return 'amber'
  return 'default'
}

function formatNumber(value: number | null): string {
  return value == null ? '未填写' : value.toLocaleString()
}
</script>

<style scoped lang="less">
.strategy-association-info {
  margin-top: 12px;
  padding: 14px;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: var(--bg);
}
.info-heading {
  margin-bottom: 10px;
  color: var(--text);
  font-weight: 600;
}
.info-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}
.info-section {
  min-width: 0;
  padding: 12px;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: #fff;
}
.section-heading {
  margin-bottom: 8px;
  color: var(--muted);
  font-size: 12px;
}
.item-title {
  display: block;
  overflow-wrap: anywhere;
}
.facts {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 6px 12px;
  margin-top: 8px;
  color: var(--muted);
  font-size: 12px;
}
.facts b {
  display: block;
  overflow-wrap: anywhere;
  color: var(--text, #1f2937);
  font-weight: 500;
}
.badges {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 8px;
}
.muted {
  color: var(--muted);
  font-size: 12px;
}
@media (max-width: 700px) {
  .info-grid,
  .facts {
    grid-template-columns: 1fr;
  }
}
</style>
