<template>
  <div>
    <!--
      模块级（T-03）：**表看模块、图看类**。
      旧审查记录里没有 modules（模块级是后加的），此时不渲染空表，只给一行提示 ——
      空表会让人以为是"这个项目没有跨模块依赖"。
    -->
    <template v-if="hasModules">
      <div class="sub-title">模块耦合度（按包聚合，表看模块 / 图看类）</div>
      <div class="module-summary">{{ result.moduleSummary }}</div>
      <a-table
        :data-source="modules"
        row-key="name"
        size="small"
        :pagination="false"
        :scroll="{ x: 'max-content' }"
      >
        <a-table-column title="模块" data-index="name" width="180">
          <template #default="{ record }">
            <span>{{ record.name }}</span>
            <a-tag v-if="record.high" color="orange" style="margin-left: 6px">高耦合</a-tag>
          </template>
        </a-table-column>
        <a-table-column title="类数" data-index="classCount" width="70" />
        <a-table-column title="Ca（被依赖）" data-index="ca" width="110" />
        <a-table-column title="Ce（依赖）" data-index="ce" width="100" />
        <a-table-column title="I（不稳定度）" data-index="instability" width="110">
          <template #default="{ text }">{{ instabilityText(text) }}</template>
        </a-table-column>
      </a-table>
      <div v-if="moduleCycles.length" class="block">
        <div class="sub-title">模块级循环依赖</div>
        <a-tooltip v-for="(cyc, i) in moduleCycles" :key="i" :title="cyc.join(' → ')">
          <a-tag color="red">{{ renderCycle(cyc) }}</a-tag>
        </a-tooltip>
      </div>
      <div class="scope-note">
        依赖按 import 统计，不含继承 / 反射 / 同包引用；高耦合模块 = Ce &gt; 3 且 I ≥ 0.8
      </div>
    </template>
    <a-alert
      v-else
      type="info"
      show-icon
      class="mb8"
      message="该记录没有模块级数据"
      description="模块级耦合是后加的，重新运行一次耦合度审查即可获得。"
    />
    <div ref="chartRef" class="graph"></div>
    <div v-if="highCoupling.length" class="block">
      <div class="sub-title">高耦合类（扇出超阈值）</div>
      <a-tooltip v-for="c in highCoupling" :key="c" :title="c">
        <a-tag color="orange">{{ shortName(c) }}</a-tag>
      </a-tooltip>
    </div>
    <div v-if="cycles.length" class="block">
      <div class="sub-title">循环依赖组</div>
      <a-tooltip v-for="(cyc, i) in cycles" :key="i" :title="cyc.join(' ↔ ')">
        <a-tag color="red">{{ cyc.map(shortName).join(' ↔ ') }}</a-tag>
      </a-tooltip>
    </div>
    <a-table
      v-if="suggestions.length"
      :data-source="suggestions"
      row-key="target"
      size="small"
      :pagination="false"
      style="margin-top: 12px"
    >
      <a-table-column title="类" data-index="target" />
      <a-table-column title="问题" data-index="issue" />
      <a-table-column title="建议" data-index="suggestion" />
      <a-table-column title="级别" data-index="severity" width="80">
        <template #default="{ text }">
          <a-tooltip v-if="severityTip(text)" :title="severityTip(text)">
            <span>{{ text }}</span>
          </a-tooltip>
          <span v-else>{{ text }}</span>
        </template>
      </a-table-column>
    </a-table>
    <RawResult v-if="result.raw" :text="result.raw" style="margin-top: 12px" />
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onBeforeUnmount, watch } from 'vue'
import * as echarts from 'echarts'
import RawResult from '@/components/RawResult.vue'
import { severityTip } from '@/utils/enums'

const props = defineProps<{ result: any }>()

const chartRef = ref<HTMLDivElement>()
let chart: echarts.ECharts | null = null

const highCoupling = computed(() => props.result?.highCoupling || [])
const cycles = computed(() => props.result?.cycles || [])
const suggestions = computed(() => props.result?.suggestions || [])
const modules = computed(() => props.result?.modules || [])
const moduleCycles = computed(() => props.result?.moduleCycles || [])
/** 只有真的拿到模块级数据才渲染这张表；旧记录走"提示 + 类级图" */
const hasModules = computed(() => Array.isArray(props.result?.modules) && props.result.modules.length > 0)

function instabilityText(value: unknown) {
  return typeof value === 'number' ? value.toFixed(2) : '—'
}

/** 2 元环读起来就是"双向依赖"，用 ↔ 更直观；更长的环画成 a → b → c → a */
function renderCycle(cycle: string[]) {
  if (cycle.length === 2) {
    return `${cycle[0]} ↔ ${cycle[1]}`
  }
  return `${cycle.join(' → ')} → ${cycle[0]}`
}

function render() {
  if (!chartRef.value) return
  if (!chart) chart = echarts.init(chartRef.value)
  const nodes = (props.result?.nodes || []).map((n: any) => {
    const fqcn = n.id || n.label || ''
    return {
      id: n.id,
      // name 必须是**全限定名**：边用 source/target（= FQCN）引用节点，改名会断连线
      name: fqcn,
      // 标签只显示类名：全限定名挤在一起既重叠又看不清
      short: n.label || shortName(fqcn),
      symbolSize: 36,
      itemStyle: { color: n.inCycle ? '#f5222d' : n.high ? '#faad14' : '#1677ff' }
    }
  })
  const edges = (props.result?.edges || []).map((e: any) => ({ source: e.source, target: e.target }))
  chart.setOption(
    {
      tooltip: {
        trigger: 'item',
        // 短名会重名（不同包的 Utils），悬停时必须能看出到底是哪个类
        formatter: (params: any) => {
          const data = params?.data || {}
          if (params?.dataType === 'edge') {
            return `${data.source} → ${data.target}`
          }
          return String(data.name || data.short || '')
        }
      },
      series: [
        {
          type: 'graph',
          layout: 'force',
          roam: true,
          draggable: true,
          data: nodes,
          links: edges,
          label: {
            show: true,
            fontSize: 11,
            formatter: (params: any) => String(params?.data?.short || params?.data?.name || '')
          },
          force: { repulsion: 200, edgeLength: 90 },
          emphasis: { focus: 'adjacency' }
        }
      ]
    },
    true
  )
}

function resize() {
  chart?.resize()
}

function shortName(fqcn: string) {
  const i = fqcn.lastIndexOf('.')
  return i >= 0 ? fqcn.slice(i + 1) : fqcn
}

/**
 * 重新计算画布尺寸。
 *
 * 只监听 window resize 是不够的：图表在 SplitPane 里，**拖动分隔条**和**切全屏**
 * 都会改变容器宽度而窗口尺寸不变，此时 canvas 还是旧尺寸 ——
 * 表现为图被裁一半或缩在角落，必须刷新页面才恢复。
 * 所以额外用 ResizeObserver 盯容器本身。
 */
let observer: ResizeObserver | null = null

onMounted(() => {
  render()
  window.addEventListener('resize', resize)
  if (typeof ResizeObserver !== 'undefined' && chartRef.value) {
    observer = new ResizeObserver(() => resize())
    observer.observe(chartRef.value)
  }
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', resize)
  observer?.disconnect()
  observer = null
  chart?.dispose()
  chart = null
})

watch(() => props.result, render, { deep: true })
</script>

<style scoped lang="less">
.graph {
  height: 320px;
  border: 1px solid #f0f0f0;
  border-radius: 4px;
}
.block {
  margin-top: 12px;
}
.sub-title {
  color: #666;
  margin-bottom: 6px;
}
.module-summary {
  color: #333;
  font-size: 12px;
  margin-bottom: 8px;
}
.scope-note {
  color: #999;
  font-size: 12px;
  margin: 8px 0 12px;
}
.mb8 {
  margin-bottom: 8px;
}
</style>
