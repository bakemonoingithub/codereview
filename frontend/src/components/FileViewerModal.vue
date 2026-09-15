<template>
  <a-modal
    :open="open"
    :title="title"
    width="80vw"
    :footer="null"
    destroy-on-close
    @cancel="close"
  >
    <div class="file-viewer">
      <div class="viewer-bar">
        <span class="path" :title="path">{{ path }}</span>
        <a-tag v-if="mode === 'diff'" color="blue">提交差异</a-tag>
        <a-tag v-else>{{ gitRef }}</a-tag>
        <span v-if="metaText" class="meta">{{ metaText }}</span>
        <a-button v-if="canLoadFull" size="small" :loading="loading" @click="load(true)">加载全文</a-button>
      </div>

      <a-spin :spinning="loading">
        <LoadErrorAlert :message="error" @retry="load(false)" />
        <template v-if="!error && !loading">
          <MarkdownView v-if="mode === 'content'" :text="markdown" />
          <DiffViewer
            v-else
            :path="path"
            :patch="result?.content || null"
            highlight
            empty-text="该文件没有可显示的差异（可能改动过大或为二进制）"
          />
        </template>
      </a-spin>
    </div>
  </a-modal>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import LoadErrorAlert from '@/components/LoadErrorAlert.vue'
import MarkdownView from '@/components/MarkdownView.vue'
import DiffViewer from '@/components/DiffViewer.vue'
import { getFileContent, type FileContent } from '@/api/project'
import { buildFileMarkdown } from '@/utils/filePreview'

/**
 * 文件查看弹窗（两种模式共用一个壳）。
 *
 * - `content`：看文件原文 —— 走 `MarkdownView`，内容由 `buildFileMarkdown` 包成一个代码块
 *   （围栏长度按内容自适应，见 `utils/filePreview`）；
 * - `diff`：看该提交对该文件的差异 —— 复用审查结果页在用的 `DiffViewer`。
 *
 * **共用一个壳**是因为"截断提示、加载全文、加载中/失败"这三件事在两种模式下完全一样，
 * 写两遍就要测两遍；差异只在中间的渲染器与数据来源。
 *
 * 为什么默认只取 1000 行、并且把总行数显示出来：一个几万行的文件既没必要整份传，
 * 用户也必须能判断"我看到的是全文还是被截了" —— 所以截断由**服务端**做并回报 `totalLines`，
 * 想要全文时由用户显式点「加载全文」（那时仍受服务端的行数/字节硬上限约束）。
 */
const props = withDefaults(
  defineProps<{
    open: boolean
    projectId: string
    /** 仓库内相对路径 */
    path: string
    /**
     * 取值引用：content 模式是分支名或 sha；diff 模式必须是 sha。
     *
     * **刻意不叫 `ref`**：`ref` 是 Vue 的保留属性（模板 ref），
     * 声明成 prop 后外部传进来的值**永远拿不到**（实测 props.ref 恒为 undefined）——
     * 这个坑在测试里表现为"接口一次都没调"，很难一眼看出来。
     */
    gitRef: string
    mode?: 'content' | 'diff'
  }>(),
  { mode: 'content' }
)

const emit = defineEmits<{ (e: 'update:open', value: boolean): void }>()

const loading = ref(false)
const error = ref('')
const result = ref<FileContent | null>(null)
/** 用户是否点了「加载全文」——避免每次重新打开都重复请求全文 */
const loadedFull = ref(false)

const title = computed(() => (props.mode === 'diff' ? '提交差异' : '文件内容'))

/** 内容模式的 Markdown（带语言围栏）；差异模式不用 */
const markdown = computed(() => (result.value ? buildFileMarkdown(props.path, result.value.content) : ''))

const canLoadFull = computed(
  () => !!result.value?.truncated && !loadedFull.value && !loading.value && !error.value
)

const metaText = computed(() => {
  const r = result.value
  if (!r) {
    return ''
  }
  if (!r.truncated) {
    return `共 ${r.totalLines} 行`
  }
  const shown = r.content ? r.content.split('\n').length : 0
  return `已显示前 ${shown} 行，共 ${r.totalLines} 行`
})

async function load(full: boolean) {
  if (!props.projectId || !props.path || !props.gitRef) {
    return
  }
  loading.value = true
  error.value = ''
  try {
    result.value = await getFileContent(props.projectId, {
      ref: props.gitRef,
      path: props.path,
      mode: props.mode,
      full
    })
    if (full) {
      loadedFull.value = true
    }
  } catch (e: any) {
    error.value = e?.message || '文件读取失败'
    result.value = null
  } finally {
    loading.value = false
  }
}

function close() {
  emit('update:open', false)
}

// 打开时按需拉取；关掉后清空，避免下次打开先闪一眼上一个文件的内容
watch(
  () => [props.open, props.path, props.gitRef, props.mode],
  ([open]) => {
    if (open) {
      loadedFull.value = false
      void load(false)
    } else {
      result.value = null
      error.value = ''
    }
  },
  { immediate: true }
)
</script>

<style scoped lang="less">
.file-viewer {
  .viewer-bar {
    display: flex;
    align-items: center;
    gap: 8px;
    margin-bottom: 8px;
  }
  .path {
    font-family: monospace;
    font-size: 13px;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .meta {
    margin-left: auto;
    color: #999;
    font-size: 12px;
    white-space: nowrap;
  }
}
</style>
