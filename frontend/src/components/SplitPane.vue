<template>
  <div class="split-wrap" :style="{ height }">
    <splitpanes class="default-theme" @resized="onResized">
      <pane :size="leftSize" min-size="20">
        <section class="pane" :class="{ 'pane-maximized': maximized === 'left' }">
          <header class="pane-head">
            <span class="pane-title">{{ leftTitle }}</span>
            <a-tooltip :title="maximized === 'left' ? '退出全屏' : '全屏'">
              <a-button type="text" size="small" @click="toggle('left')">
                <FullscreenExitOutlined v-if="maximized === 'left'" />
                <FullscreenOutlined v-else />
              </a-button>
            </a-tooltip>
          </header>
          <div class="pane-body">
            <slot name="left" />
          </div>
        </section>
      </pane>
      <pane :size="100 - leftSize" min-size="20">
        <section class="pane" :class="{ 'pane-maximized': maximized === 'right' }">
          <header class="pane-head">
            <span class="pane-title">{{ rightTitle }}</span>
            <a-tooltip :title="maximized === 'right' ? '退出全屏' : '全屏'">
              <a-button type="text" size="small" @click="toggle('right')">
                <FullscreenExitOutlined v-if="maximized === 'right'" />
                <FullscreenOutlined v-else />
              </a-button>
            </a-tooltip>
          </header>
          <div class="pane-body">
            <slot name="right" />
          </div>
        </section>
      </pane>
    </splitpanes>
    <!-- 全屏遮罩：点击退出。用固定定位放大「同一份 DOM」，避免重复挂载插槽内容造成双份请求 -->
    <div v-if="maximized" class="fs-mask" @click="maximized = null"></div>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { Pane, Splitpanes } from 'splitpanes'
import 'splitpanes/dist/splitpanes.css'
import { FullscreenExitOutlined, FullscreenOutlined } from '@ant-design/icons-vue'

const props = withDefaults(
  defineProps<{
    leftTitle?: string
    rightTitle?: string
    height?: string
    storageKey?: string
  }>(),
  {
    leftTitle: '',
    rightTitle: '',
    height: 'calc(100vh - 250px)',
    storageKey: 'dsh:project-detail:split'
  }
)

const maximized = ref<'left' | 'right' | null>(null)

function readSize(): number {
  try {
    const raw = localStorage.getItem(props.storageKey)
    const value = raw ? Number(raw) : NaN
    if (!Number.isNaN(value) && value >= 20 && value <= 80) {
      return value
    }
  } catch {
    /* localStorage 不可用时用默认值 */
  }
  return 40
}

// 刻意用非响应式初值：拖动后不再回写 :size，避免与 splitpanes 内部状态互相触发
const leftSize = readSize()

function toggle(side: 'left' | 'right') {
  maximized.value = maximized.value === side ? null : side
}

function onResized(event: any) {
  const panes = event?.panes || event
  const size = Array.isArray(panes) ? panes[0]?.size : undefined
  if (typeof size === 'number' && size > 0) {
    try {
      localStorage.setItem(props.storageKey, String(Math.round(size)))
    } catch {
      /* 忽略持久化失败 */
    }
  }
}
</script>

<style scoped lang="less">
.split-wrap {
  position: relative;
  width: 100%;
}
.pane {
  display: flex;
  flex-direction: column;
  height: 100%;
  overflow: hidden;
  background: #fff;
  border: 1px solid #f0f0f0;
  border-radius: 6px;
}
/* 「全屏」= 把同一份 DOM 固定定位铺满视口，视觉上等同全屏模态框 */
.pane-maximized {
  position: fixed;
  inset: 0;
  z-index: 1001;
  border: none;
  border-radius: 0;
}
.fs-mask {
  position: fixed;
  inset: 0;
  z-index: 1000;
  background: rgba(0, 0, 0, 0.45);
}
.pane-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex: none;
  padding: 4px 8px;
  border-bottom: 1px solid #f0f0f0;
  background: #fafafa;
}
.pane-title {
  font-weight: 600;
  font-size: 13px;
}
.pane-body {
  flex: 1;
  min-height: 0;
  overflow: auto;
  padding: 8px;
}
:deep(.splitpanes__splitter) {
  width: 6px;
  background: transparent;
}
:deep(.splitpanes__splitter:hover) {
  background: #e6f4ff;
}
</style>
