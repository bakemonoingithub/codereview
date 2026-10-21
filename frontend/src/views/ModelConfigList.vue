<template>
  <ListPageLayout
    title="模型管理"
    subtitle="维护接入地址、凭据与容量档案。验证记录说明实际检查范围。"
  >
    <template #actions>
      <a-button type="primary" @click="openCreate">＋ 新建模型</a-button>
    </template>

    <template #notice>
      <PageNotice>配置模型后可在审查策略和报告表单中选用。凭据只写入，不回显正文或带入历史输入。</PageNotice>
    </template>

    <template #filters>
      <a-input-search
        v-model:value="keyword"
        class="filter-search"
        placeholder="搜索名称或模型名"
        allow-clear
        @search="reload"
      />
    </template>

    <LoadErrorAlert :message="loadError" @retry="load" />

    <a-table :data-source="records" row-key="id" :loading="loading" :pagination="false">
      <template #emptyText>
        <EmptyGuide
          :title="keyword ? '没有匹配的模型' : '还没有接入模型'"
          :hint="keyword ? '换个关键词，或清空搜索看全部' : '填入模型网关地址、模型名与凭据后，才能在策略里选用'"
          :action-text="keyword ? '' : '新建模型'"
          @action="openCreate"
        />
      </template>
      <a-table-column title="名称 / 模型名">
        <template #default="{ record }">
          <strong>{{ record.name }}</strong>
          <div class="cell-sub">{{ record.modelName }}</div>
        </template>
      </a-table-column>
      <a-table-column title="接口地址" ellipsis>
        <template #default="{ record }">
          {{ record.baseUrl }}
          <div class="cell-sub">凭据：{{ record.credentialConfigured ? '已配置' : '未配置' }}</div>
        </template>
      </a-table-column>
      <a-table-column title="容量依据" width="210">
        <template #default="{ record }">
          {{ formatNumber(record.contextWindow) }} / {{ formatNumber(record.maxOutput) }}
          <div class="cell-sub">
            窗口 / 最大输出 tokens · {{ record.tokenizer || '保守估算' }}
          </div>
        </template>
      </a-table-column>
      <a-table-column title="验证状态" width="170">
        <template #default="{ record }">
          <StatusBadge :tone="validationTone(record.validationStatus)">
            {{ validationLabel(record.validationStatus) }}
          </StatusBadge>
          <div class="cell-sub">可达性与简单 JSON；未验证完整窗口</div>
        </template>
      </a-table-column>
      <a-table-column title="操作" width="210">
        <template #default="{ record }">
          <a-button type="link" size="small" @click="openEdit(record)">编辑</a-button>
          <a-button
            type="link"
            size="small"
            :loading="validatingId === record.id"
            :disabled="!!validatingId"
            @click="onValidate(record)"
          >
            验证
          </a-button>
          <a-popconfirm title="删除后历史调用与任务快照不受影响，确认删除？" @confirm="onDelete(record.id)">
            <a-button type="link" size="small" danger>删除</a-button>
          </a-popconfirm>
        </template>
      </a-table-column>
    </a-table>

    <div v-if="hasMore" class="load-more">
      <a-button :loading="loading" @click="loadMore">加载更多</a-button>
    </div>

    <a-modal
      v-model:open="modalOpen"
      :title="editing ? '编辑模型' : '新建模型'"
      :confirm-loading="saving"
      wrap-class-name="admin-form-modal"
      @ok="onSave"
    >
      <a-form class="form-grid" layout="vertical">
        <a-form-item label="名称" required>
          <a-input v-model:value="form.name" placeholder="如 默认模型" />
        </a-form-item>
        <a-form-item label="模型名" required>
          <a-input v-model:value="form.modelName" placeholder="deepseek-chat" />
        </a-form-item>
        <a-form-item label="接口地址" required>
          <a-input v-model:value="form.baseUrl" placeholder="https://gateway.example.com/v1" />
        </a-form-item>
        <a-form-item label="凭据">
          <a-input-password
            v-model:value="form.credential"
            :placeholder="editing && credentialConfigured ? '已配置，留空不修改' : '只写入，不回显'"
            autocomplete="new-password"
          />
        </a-form-item>
        <a-form-item label="上下文窗口（tokens）">
          <a-input v-model:value="form.contextWindow" placeholder="如 32768" />
        </a-form-item>
        <a-form-item label="最大输出（tokens）">
          <a-input v-model:value="form.maxOutput" placeholder="如 4096" />
        </a-form-item>
        <a-form-item class="span-2" label="计量方式">
          <a-input v-model:value="form.tokenizer" placeholder="留空使用保守估算" />
          <div class="field-hint">未适配 tokenizer 时按 UTF-8 字节上界估算，界面记为估算值。</div>
        </a-form-item>
        <a-form-item class="span-2" label="协议">
          <a-select v-model:value="form.protocol" :options="protocolOptions" />
          <div class="field-hint">当前公共调用只接入 OpenAI Chat 兼容协议。</div>
        </a-form-item>
        <a-form-item v-if="editing" class="span-2">
          <span class="field-hint">保存后配置修订号增加，旧的验证记录自动过期。</span>
        </a-form-item>
      </a-form>
    </a-modal>
  </ListPageLayout>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { message } from 'ant-design-vue'
import { modelsApi, type ModelView } from '@/api/v2'
import ListPageLayout from '@/components/ListPageLayout.vue'
import EmptyGuide from '@/components/EmptyGuide.vue'
import LoadErrorAlert from '@/components/LoadErrorAlert.vue'
import PageNotice from '@/components/PageNotice.vue'
import StatusBadge from '@/components/StatusBadge.vue'

const records = ref<ModelView[]>([])
const loading = ref(false)
const loadError = ref('')
const keyword = ref('')
const nextCursor = ref<string | null>(null)
const modalOpen = ref(false)
const saving = ref(false)
const validatingId = ref('')
const editing = ref<ModelView | null>(null)
const credentialConfigured = ref(false)
const form = ref({
  name: '',
  baseUrl: 'https://api.deepseek.com',
  modelName: 'deepseek-chat',
  credential: '',
  contextWindow: '',
  maxOutput: '',
  tokenizer: '',
  protocol: 'OPENAI_CHAT'
})
const protocolOptions = [{ value: 'OPENAI_CHAT', label: 'OpenAI Chat 兼容' }]
const hasMore = computed(() => !!nextCursor.value)
let listGeneration = 0

const validationLabels: Record<string, { label: string; tone: 'green' | 'amber' | 'red' | 'default' }> = {
  PASSED: { label: '验证通过', tone: 'green' },
  FAILED: { label: '验证失败', tone: 'red' },
  EXPIRED: { label: '配置已变更，验证过期', tone: 'amber' },
  NONE: { label: '未验证', tone: 'amber' }
}

function validationLabel(status: string) {
  return validationLabels[status]?.label || status
}

function validationTone(status: string): 'green' | 'amber' | 'red' | 'default' {
  return validationLabels[status]?.tone || 'default'
}

function formatNumber(value: number | null) {
  return value == null ? '未填写' : value.toLocaleString()
}

async function load(cursor?: string) {
  if (cursor && loading.value) return
  const generation = ++listGeneration
  if (!cursor) nextCursor.value = null
  loading.value = true
  loadError.value = ''
  try {
    const page = await modelsApi.list({
      q: keyword.value.trim() || undefined,
      cursor,
      limit: 100
    })
    if (generation !== listGeneration) return
    records.value = cursor ? [...records.value, ...page.items] : page.items
    nextCursor.value = page.nextCursor
  } catch (e: any) {
    if (generation !== listGeneration) return
    loadError.value = e?.message || '模型列表加载失败'
  } finally {
    if (generation === listGeneration) loading.value = false
  }
}

function reload() { return load() }

function loadMore() {
  if (nextCursor.value) load(nextCursor.value)
}

function openCreate() {
  editing.value = null
  credentialConfigured.value = false
  form.value = {
    name: '',
    baseUrl: 'https://api.deepseek.com',
    modelName: 'deepseek-chat',
    credential: '',
    contextWindow: '',
    maxOutput: '',
    tokenizer: '',
    protocol: 'OPENAI_CHAT'
  }
  modalOpen.value = true
}

function openEdit(record: ModelView) {
  editing.value = record
  credentialConfigured.value = record.credentialConfigured
  form.value = {
    name: record.name,
    baseUrl: record.baseUrl,
    modelName: record.modelName,
    credential: '',
    contextWindow: record.contextWindow == null ? '' : String(record.contextWindow),
    maxOutput: record.maxOutput == null ? '' : String(record.maxOutput),
    tokenizer: record.tokenizer || '',
    protocol: record.protocol || 'OPENAI_CHAT'
  }
  modalOpen.value = true
}

function optionalNumber(value: string) {
  const trimmed = value.trim()
  if (!trimmed) return null
  const parsed = Number(trimmed)
  return Number.isFinite(parsed) && parsed > 0 ? parsed : null
}

async function onSave() {
  if (!form.value.name.trim() || !form.value.baseUrl.trim() || !form.value.modelName.trim()) {
    message.warning('请填写名称、模型名和接口地址')
    return
  }
  saving.value = true
  try {
    const payload = {
      name: form.value.name.trim(),
      baseUrl: form.value.baseUrl.trim(),
      modelName: form.value.modelName.trim(),
      credential: form.value.credential.trim() || undefined,
      contextWindow: optionalNumber(form.value.contextWindow),
      maxOutput: optionalNumber(form.value.maxOutput),
      tokenizer: form.value.tokenizer.trim() || undefined,
      protocol: form.value.protocol
    }
    if (editing.value) {
      await modelsApi.update(editing.value.id, {
        ...payload,
        expectedRevision: editing.value.revision
      })
      message.success('保存成功')
    } else {
      await modelsApi.create(payload)
      message.success('创建成功')
    }
    modalOpen.value = false
    await load()
  } catch (e: any) {
    message.error(e?.message || '保存失败')
  } finally {
    saving.value = false
  }
}

async function onValidate(record: ModelView) {
  if (validatingId.value) return
  validatingId.value = record.id
  try {
    const result = await modelsApi.validate(record.id)
    if (result.result === 'PASSED') {
      message.success(`验证通过（配置修订 ${result.configRevision}）：${result.detail}`)
    } else {
      message.error(`验证失败：${result.detail}`)
    }
  } catch (e: any) {
    message.error(e?.message || '验证失败')
  } finally {
    validatingId.value = ''
    await load()
  }
}

async function onDelete(id: string) {
  try {
    await modelsApi.remove(id)
    message.success('删除成功')
    await load()
  } catch (e: any) {
    message.error(e?.message || '删除失败')
  }
}

onMounted(load)
</script>

<style scoped lang="less">
.field-hint {
  margin-top: 4px;
  color: var(--muted);
  font-size: 12px;
}
.load-more {
  display: flex;
  justify-content: center;
  margin-top: 16px;
}
</style>
