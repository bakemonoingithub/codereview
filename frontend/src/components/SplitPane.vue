<template>
  <div ref="wrapRef" class="split-wrap" :style="wrapStyle">
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
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { Pane, Splitpanes } from 'splitpanes'
import 'splitpanes/dist/splitpanes.css'
import { FullscreenExitOutlined, FullscreenOutlined } from '@ant-design/icons-vue'

const props = withDefaults(
  defineProps<{
    leftTitle?: string
    rightTitle?: string
    /**
     * 显式高度。弹窗里传 '100%' 撑满模态框；
     * 不传则由组件自己按元素顶部位置算，避免再写死 `calc(100vh - 250px)`
     * 这类「换个页签高度就对不上」的魔法值。
     */
    height?: string
    storageKey?: string
  }>(),
  {
    leftTitle: '',
    rightTitle: '',
    height: '',
    storageKey: 'dsh:project-detail:split'
  }
)

/** 兜底最小高度：窗口过矮时至少留出可用的滚动区 */
const MIN_HEIGHT = 320
/** 底部留白，避免贴住视口下沿 */
const BOTTOM_GAP = 24

const wrapRef = ref<HTMLElement | null>(null)
const autoHeight = ref('')

const wrapStyle = computed(() =>
  props.height ? { height: props.height } : { height: autoHeight.value }
)

/**
 * 按元素当前顶部位置撑满剩余视口。
 * 之所以量位置而不是写死 `100vh - Npx`：同一组件在页签里、弹窗里的
 * 头部高度完全不同，写死的 N 必然在其中一处错位。
 */
function measure() {
  if (props.height) return
  const el = wrapRef.value
  if (!el || typeof el.getBoundingClientRect !== 'function') return
  const top = el.getBoundingClientRect().top
  autoHeight.value = `${Math.max(MIN_HEIGHT, Math.floor(window.innerHeight - top - BOTTOM_GAP))}px`
}

onMounted(() => {
  measure()
  // 首帧后字体/图标落地可能让顶部位置变化，补量一次
  if (typeof requestAnimationFrame === 'function') {
    requestAnimationFrame(measure)
  }
  window.addEventListener('resize', measure)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', measure)
})

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
