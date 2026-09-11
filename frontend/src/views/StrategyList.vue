<template>
  <ListPageLayout title="审查策略" subtitle="把「模型 + 提示词」或「外部 API」组装成一次可复用的审查配置">
    <template #actions>
      <a-button type="primary" @click="openCreate">新建策略</a-button>
    </template>

    <template #filters>
      <a-space>
        <a-input v-model:value="keyword" placeholder="搜索名称" style="width: 240px" allow-clear @pressEnter="load" />
        <a-select v-model:value="filterAnalyzerType" placeholder="分析器" style="width: 180px" allow-clear>
          <a-select-option v-for="a in ANALYZER_TYPES" :key="a.value" :value="a.value">{{ a.label }}</a-select-option>
        </a-select>
        <a-button type="primary" @click="load">搜索</a-button>
        <a-button @click="resetSearch">重置</a-button>
      </a-space>
    </template>

    <LoadErrorAlert :message="loadError" @retry="load" />

    <a-table :data-source="records" row-key="id" :loading="loading" :pagination="false">
      <template #emptyText>
        <EmptyGuide
          title="还没有审查策略"
          hint="策略 = 模型 + 提示词（或外部 API）。建好之后在项目详情页选它就能开始审查"
          action-text="新建策略"
          @action="openCreate"
        />
      </template>
      <a-table-column title="名称" data-index="name" />
      <a-table-column title="分析器" data-index="analyzerType">
        <template #default="{ text }">
          <!-- 保留英文枚举（与后端 AnalyzerTypes / 日志对应），中文放 tooltip -->
          <a-tooltip :title="analyzerName(text)">
            <span>{{ analyzerLabel(text) }}</span>
          </a-tooltip>
        </template>
      </a-table-column>
      <a-table-column title="创建时间" data-index="createdAt" />
      <a-table-column title="操作">
        <template #default="{ record }">
          <a-space>
            <a-button size="small" @click="openEdit(record)">编辑</a-button>
            <a-popconfirm title="确认删除？" @confirm="onDelete(record.id)">
              <a-button size="small" danger>删除</a-button>
            </a-popconfirm>
          </a-space>
        </template>
      </a-table-column>
    </a-table>

    <a-modal v-model:open="modalOpen" :title="editingId ? '编辑策略' : '新建策略'" :confirm-loading="saving" @ok="onSave">
      <a-form layout="vertical">
        <a-form-item label="名称" required>
          <a-input v-model:value="form.name" placeholder="如 通用代码审查" />
        </a-form-item>
        <a-form-item label="分析器" required>
          <a-select v-model:value="form.analyzerType" placeholder="选择分析器" :disabled="!!editingId">
            <a-select-option v-for="a in ANALYZER_TYPES" :key="a.value" :value="a.value">{{ a.label }}</a-select-option>
          </a-select>
        </a-form-item>

        <template v-if="form.analyzerType !== 4">
          <a-form-item label="模型" required>
            <a-select v-model:value="form.modelConfigId" placeholder="选择模型" :options="modelOptions">
              <template #notFoundContent>
                <div class="not-found">
                  <span>暂无可用模型</span>
                  <router-link to="/models">去创建模型</router-link>
                </div>
              </template>
            </a-select>
            <!-- 依赖链断点：模型表拉取失败时原先只弹"请选择模型"，不说为什么、也没地方点进去 -->
            <div v-if="modelLoadError" class="form-hint-error">
              {{ modelLoadError }}，<router-link to="/models">去模型页重试</router-link>
            </div>
          </a-form-item>
          <a-form-item label="关注点提示词（可选）">
            <a-select v-model:value="form.promptId" placeholder="选择提示词" :options="promptOptions" allow-clear>
              <template #notFoundContent>
                <div class="not-found">
                  <span>暂无提示词</span>
                  <router-link to="/prompts">去创建提示词</router-link>
                </div>
              </template>
            </a-select>
            <div v-if="promptLoadError" class="form-hint-error">
              {{ promptLoadError }}，<router-link to="/prompts">去提示词页重试</router-link>
            </div>
          </a-form-item>
          <a-form-item v-if="form.analyzerType === 2" label="高耦合阈值（扇出超过即标记）">
            <a-input v-model:value="form.threshold" placeholder="默认 10" />
          </a-form-item>
          <a-form-item v-if="form.analyzerType === 5" label="方法体窗口（变更行 ± N 行，超长方法体按此截断）">
            <a-input v-model:value="form.methodWindowLines" placeholder="默认 60" />
          </a-form-item>
        </template>

        <template v-else>
          <a-form-item label="调用 API" required>
            <a-input v-model:value="form.apiUrl" placeholder="触发流水线的 API 地址" />
          </a-form-item>
          <a-form-item label="结果展示地址" required>
            <a-input v-model:value="form.resultUrl" placeholder="SonarQube 结果页 URL" />
          </a-form-item>
          <a-form-item label="查询具体结果地址">
            <a-input v-model:value="form.queryUrl" placeholder="SonarQube Web API 地址（可选）" />
          </a-form-item>
          <a-form-item label="Token">
            <a-input-password
              v-model:value="form.token"
              :placeholder="tokenConfigured ? '••••••••（已配置，留空不修改）' : '只读 token（可选）'"
              :disabled="clearToken"
            />
            <a-checkbox v-if="tokenConfigured" v-model:checked="clearToken" style="margin-top: 6px">
              清除 token
            </a-checkbox>
          </a-form-item>
        </template>
      </a-form>
    </a-modal>
  </ListPageLayout>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { message } from 'ant-design-vue'
import { listStrategies, createStrategy, updateStrategy, deleteStrategy, ANALYZER_TYPES, analyzerLabel } from '@/api/strategy'
import ListPageLayout from '@/components/ListPageLayout.vue'
import EmptyGuide from '@/components/EmptyGuide.vue'
import { listModels } from '@/api/model'
import { listPrompts } from '@/api/prompt'
import { analyzerName } from '@/utils/accuracy'
import LoadErrorAlert from '@/components/LoadErrorAlert.vue'

const records = ref<any[]>([])
const loading = ref(false)
// 原先 load 没有 catch：请求失败时表格永远空着，且不给任何提示与重试入口
const loadError = ref('')
const keyword = ref('')
const filterAnalyzerType = ref<number | undefined>(undefined)
const modalOpen = ref(false)
const saving = ref(false)
const editingId = ref('')
const form = ref({
  name: '',
  analyzerType: 1,
  modelConfigId: '',
  promptId: '',
  threshold: '10',
  methodWindowLines: '60',
  apiUrl: '',
  resultUrl: '',
  queryUrl: '',
  token: ''
})
const modelOptions = ref<{ value: string; label: string }[]>([])
const promptOptions = ref<{ value: string; label: string }[]>([])
/**
 * api-review 的 token 只写不读：服务端不再回传明文，因此编辑时**没有**可预填的值。
 * 用 hasToken 标记显示"已配置"，留空即表示不修改；要删掉就勾"清除 token"。
 */
const tokenConfigured = ref(false)
const clearToken = ref(false)
/** 模态框里两个依赖下拉的加载失败：失败要说清原因并给出入口，而不是让下拉空着 */
const modelLoadError = ref('')
const promptLoadError = ref('')

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    const params: Record<string, any> = { pageNum: 1, pageSize: 100 }
    const kw = keyword.value.trim()
    if (kw) params.keyword = kw
    if (filterAnalyzerType.value !== undefined && filterAnalyzerType.value !== null) {
      params.analyzerType = filterAnalyzerType.value
    }
    const page = (await listStrategies(params)) as any
    records.value = page?.records || []
  } catch (e: any) {
    loadError.value = e?.message || '策略列表加载失败'
  } finally {
    loading.value = false
  }
}

function resetSearch() {
  keyword.value = ''
  filterAnalyzerType.value = undefined
  load()
}

async function loadModels() {
  modelLoadError.value = ''
  try {
    const page = (await listModels({ pageNum: 1, pageSize: 100 })) as any
    modelOptions.value = (page?.records || []).map((m: any) => ({ value: m.id, label: m.name }))
  } catch (e: any) {
    // 原先这里连 try/catch 都没有：失败即未处理的 rejection，下拉静默为空
    modelLoadError.value = e?.message || '模型列表加载失败'
  }
}

async function loadPrompts() {
  promptLoadError.value = ''
  try {
    const page = (await listPrompts({ pageNum: 1, pageSize: 100 })) as any
    promptOptions.value = (page?.records || []).map((p: any) => ({ value: p.id, label: p.name }))
  } catch (e: any) {
    promptLoadError.value = e?.message || '提示词列表加载失败'
  }
}

function openCreate() {
  editingId.value = ''
  tokenConfigured.value = false
  clearToken.value = false
  form.value = {
    name: '',
    analyzerType: 1,
    modelConfigId: '',
    promptId: '',
    threshold: '10',
    methodWindowLines: '60',
    apiUrl: '',
    resultUrl: '',
    queryUrl: '',
    token: ''
  }
  modalOpen.value = true
  loadModels()
  loadPrompts()
}

function openEdit(record: any) {
  editingId.value = record.id
  let params: Record<string, any> = {}
  try {
    params = JSON.parse(record.paramsJson || '{}')
  } catch {
    params = {}
  }
  // token 不再回传，这里只剩 hasToken 标记；输入框留空
  tokenConfigured.value = !!record.hasToken
  clearToken.value = false
  form.value = {
    name: record.name,
    analyzerType: record.analyzerType,
    modelConfigId: params.modelConfigId || '',
    promptId: params.promptId || params.promptVersionId || '',
    threshold: params.threshold != null ? String(params.threshold) : '10',
    methodWindowLines: params.methodWindowLines != null ? String(params.methodWindowLines) : '60',
    apiUrl: params.apiUrl || '',
    resultUrl: params.resultUrl || '',
    queryUrl: params.queryUrl || '',
    token: ''
  }
  modalOpen.value = true
  loadModels()
  loadPrompts()
}

async function onSave() {
  if (!form.value.name) {
    message.warning('请填写名称')
    return
  }
  const analyzerType = Number(form.value.analyzerType)
  const params: Record<string, any> = {}
  if (analyzerType === 4) {
    if (!form.value.apiUrl || !form.value.resultUrl) {
      message.warning('请填写调用 API 与结果展示地址')
      return
    }
    params.apiUrl = form.value.apiUrl
    params.resultUrl = form.value.resultUrl
    if (form.value.queryUrl) params.queryUrl = form.value.queryUrl
    // token 三态：勾了清除 → clearToken；重填了 → 覆盖；都没做 → 不下发，由服务端沿用原值
    if (clearToken.value) {
      params.clearToken = true
    } else if (form.value.token) {
      params.token = form.value.token
    }
  } else {
    if (!form.value.modelConfigId) {
      message.warning('请选择模型')
      return
    }
    params.modelConfigId = form.value.modelConfigId
    if (form.value.promptId) params.promptId = form.value.promptId
    if (analyzerType === 2) {
      const t = Number(form.value.threshold)
      if (!Number.isNaN(t) && t > 0) params.threshold = t
    }
    if (analyzerType === 5) {
      const w = Number(form.value.methodWindowLines)
      if (!Number.isNaN(w) && w > 0) params.methodWindowLines = w
    }
  }
  saving.value = true
  try {
    if (editingId.value) {
      await updateStrategy(editingId.value, { name: form.value.name, params })
      message.success('保存成功')
    } else {
      await createStrategy({ name: form.value.name, analyzerType, params })
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

async function onDelete(id: string) {
  try {
    await deleteStrategy(id)
    message.success('删除成功')
    await load()
  } catch (e: any) {
    message.error(e?.message || '删除失败')
  }
}

onMounted(load)
</script>

<style scoped lang="less">
.not-found {
  padding: 4px 0;
  text-align: center;
  color: #8c8c8c;
  a {
    margin-left: 6px;
  }
}
.form-hint-error {
  margin-top: 4px;
  color: #cf1322;
  font-size: 12px;
}
</style>
