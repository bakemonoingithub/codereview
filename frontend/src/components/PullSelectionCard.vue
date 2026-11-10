<template>
  <section v-if="pullNumber != null || error || notice || moved" class="pull-selection-card" aria-label="PR 信息">
    <header v-if="pullNumber != null" class="pr-header">
      <span class="pr-icon" aria-hidden="true">
        <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round">
          <circle cx="6" cy="5" r="2" />
          <circle cx="6" cy="19" r="2" />
          <circle cx="18" cy="19" r="2" />
          <path d="M6 7v10M18 17v-5a5 5 0 0 0-5-5h-2m2-2-2 2 2 2" />
        </svg>
      </span>
      <div class="pr-identity">
        <p class="pr-label">待合并 PR <code class="pr-number" :aria-label="`PR 编号 ${pullNumber}`">#{{ pullNumber }}</code></p>
        <h3 v-if="title" class="pr-title">{{ title }}</h3>
      </div>
      <a v-if="openUrl" class="pr-link" :href="openUrl" target="_blank" rel="noopener noreferrer" :aria-label="`在新标签页打开 PR #${pullNumber}`">
        打开 PR
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
          <path d="M13 5h6v6m0-6-8 8m7 1v4a1 1 0 0 1-1 1H6a1 1 0 0 1-1-1V7a1 1 0 0 1 1-1h4" />
        </svg>
      </a>
    </header>

    <div class="card-body">
      <template v-if="pullNumber != null">
        <div class="card-section">
          <h4>源码版本</h4>
          <dl class="version-list">
            <div v-for="version in versions" :key="version.label" class="version">
              <dt>{{ version.label }}</dt>
              <dd>
                <code :title="version.sha || undefined">{{ version.sha.slice(0, 10) || '未记录' }}</code>
                <span class="detail">{{ version.description }}</span>
              </dd>
            </div>
          </dl>
<!--          <p class="comparison">比较共同祖先与源提交，得到 PR 的净变化。</p>-->
        </div>

        <div class="card-section">
          <h4>净变化文件</h4>
          <dl class="file-list">
            <div v-for="file in fileCounts" :key="file.label" class="file-count" :class="{ eligible: file.eligible }">
              <dt>{{ file.label }}</dt>
              <dd>
                <span class="number">{{ file.count }}</span><span class="unit">个</span>
                <span class="detail">{{ file.eligible ? '可纳入审查' : '不纳入审查' }}</span>
              </dd>
            </div>
          </dl>
        </div>
      </template>

      <div v-if="error || notice || moved" class="card-alerts">
        <a-alert v-if="error" type="error" show-icon :message="error" />
        <a-alert v-if="notice" type="info" show-icon :message="notice" />
        <a-alert v-if="moved" type="warning" show-icon message="PR 版本已变化，请重新确认范围后再发起。" />
      </div>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{
  pullNumber: number | null
  title: string
  pullUrl: string
  sourceHead: string
  targetHead: string
  mergeBase: string
  textFiles: number
  binaryFiles: number
  unreadableFiles: number
  error: string
  notice: string
  moved: boolean
}>()

const openUrl = computed(() => /^https?:\/\//i.test(props.pullUrl) ? props.pullUrl : '')

const versions = computed(() => [
  { label: '源提交', sha: props.sourceHead, description: '源分支待合入的版本' },
  { label: '目标提交', sha: props.targetHead, description: '目标分支的当前版本' },
  { label: '共同祖先', sha: props.mergeBase, description: '计算净变化的起点' }
])

const fileCounts = computed(() => [
  { label: '文本文件', count: props.textFiles, eligible: true },
  { label: '二进制文件', count: props.binaryFiles, eligible: false },
  { label: '无法读取的文件', count: props.unreadableFiles, eligible: false }
])
</script>

<style scoped>
.pull-selection-card {
  --pr-muted: #52617a;
  --pr-line: #e3e9f1;
  --pr-blue: #234fb0;
  container-type: inline-size;
  margin-top: 8px;
  overflow: hidden;
  color: #18243a;
  line-height: 1.5;
  background: #fff;
  border: 1px solid #dce4ef;
  border-radius: 16px;
  box-shadow: 0 12px 36px rgb(21 45 87 / 8%);
}

.pr-header {
  display: grid;
  grid-template-columns: 40px minmax(0, 1fr) auto;
  align-items: center;
  gap: 14px;
  padding: 22px 24px;
  background: #f7faff;
  border-bottom: 1px solid var(--pr-line);
}

.pr-icon {
  display: grid;
  width: 40px;
  height: 40px;
  place-items: center;
  border-radius: 11px;
  color: var(--pr-blue);
  background: #e8efff;
}

.pr-identity { min-width: 0; }
.pr-label {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  margin: 0;
  color: var(--pr-muted);
  font-size: 12px;
  font-weight: 600;
}

.pr-number {
  padding: 1px 7px;
  color: var(--pr-blue);
  background: #e8efff;
  border-radius: 5px;
}

.pr-link {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 7px 12px;
  color: var(--pr-blue);
  background: #fff;
  border: 1px solid #c6d7f8;
  border-radius: 8px;
  font-size: 13px;
  font-weight: 600;
  text-decoration: none;
  white-space: nowrap;
}

.pr-link:hover { color: var(--pr-blue); background: #e8efff; }
.pr-link:focus-visible { outline: 2px solid var(--pr-blue); outline-offset: 2px; }

.pr-title {
  margin: 4px 0 0;
  color: inherit;
  font-size: 19px;
  font-weight: 700;
  line-height: 1.45;
  overflow-wrap: anywhere;
}

h4 { margin: 0 0 10px; color: inherit; font-size: 14px; font-weight: 700; }
.card-body { padding: 20px 24px 22px; }
.card-section + .card-section, .card-section + .card-alerts { margin-top: 18px; }
.card-alerts { display: grid; gap: 8px; }

.version-list, .file-list {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  margin: 0;
}

.version-list { gap: 8px; }
.version {
  padding: 10px 12px;
  background: #f7f9fc;
  border: 1px solid var(--pr-line);
  border-radius: 9px;
}

dt { font-size: 12px; font-weight: 600; }
dd { margin: 4px 0 0; }
code { font-family: Consolas, "SFMono-Regular", monospace; }
.version code { color: var(--pr-blue); font-size: 13px; overflow-wrap: anywhere; }
.detail, .comparison { color: var(--pr-muted); font-size: 12px; }
.detail { display: block; margin-top: 2px; }
.comparison { margin: 8px 0 0; }

.file-list { border: 1px solid var(--pr-line); border-radius: 9px; }
.file-count { padding: 9px 12px; }
.file-count + .file-count { border-left: 1px solid var(--pr-line); }
.number { font-size: 20px; font-weight: 700; line-height: 1.2; font-variant-numeric: tabular-nums; }
.unit { margin-left: 4px; color: var(--pr-muted); font-size: 12px; }
.eligible .number { color: var(--pr-blue); }

@container (max-width: 480px) {
  .pr-header, .card-body { padding: 16px; }
  .pr-header { grid-template-columns: 40px minmax(0, 1fr); row-gap: 10px; }
  .pr-link { grid-column: 2; justify-self: start; }
  .version-list { grid-template-columns: 1fr; }
  .version { display: grid; grid-template-columns: 5em minmax(0, 1fr); gap: 8px; }
  .version dd { margin: 0; }
  .file-count { padding: 8px; }
}
</style>
