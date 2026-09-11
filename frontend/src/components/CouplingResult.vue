<template>
  <div>
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
</style>
