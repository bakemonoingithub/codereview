<template>
  <div>
    <div ref="chartRef" class="graph"></div>
    <div v-if="highCoupling.length" class="block">
      <div class="sub-title">高耦合类（扇出超阈值）</div>
      <a-tag v-for="c in highCoupling" :key="c" color="orange">{{ shortName(c) }}</a-tag>
    </div>
    <div v-if="cycles.length" class="block">
      <div class="sub-title">循环依赖组</div>
      <a-tag v-for="(cyc, i) in cycles" :key="i" color="red">{{ cyc.map(shortName).join(' ↔ ') }}</a-tag>
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
      <a-table-column title="级别" data-index="severity" width="80" />
    </a-table>
    <RawResult v-if="result.raw" :text="result.raw" style="margin-top: 12px" />
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onBeforeUnmount, watch } from 'vue'
import * as echarts from 'echarts'
import RawResult from '@/components/RawResult.vue'

const props = defineProps<{ result: any }>()

const chartRef = ref<HTMLDivElement>()
let chart: echarts.ECharts | null = null

const highCoupling = computed(() => props.result?.highCoupling || [])
const cycles = computed(() => props.result?.cycles || [])
const suggestions = computed(() => props.result?.suggestions || [])

function render() {
  if (!chartRef.value) return
  if (!chart) chart = echarts.init(chartRef.value)
  const nodes = (props.result?.nodes || []).map((n: any) => ({
    id: n.id,
    name: n.label,
    symbolSize: 36,
    itemStyle: { color: n.inCycle ? '#f5222d' : n.high ? '#faad14' : '#1677ff' }
  }))
  const edges = (props.result?.edges || []).map((e: any) => ({ source: e.source, target: e.target }))
  chart.setOption(
    {
      tooltip: { trigger: 'item' },
      series: [
        {
          type: 'graph',
          layout: 'force',
          roam: true,
          draggable: true,
          data: nodes,
          links: edges,
          label: { show: true, fontSize: 11 },
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

onMounted(() => {
  render()
  window.addEventListener('resize', resize)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', resize)
  chart?.dispose()
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
