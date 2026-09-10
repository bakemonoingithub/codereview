<template>
  <a-config-provider :locale="zhCN">
    <router-view />
  </a-config-provider>
</template>

<script setup lang="ts">
import zhCN from 'ant-design-vue/es/locale/zh_CN'
import dayjs from 'dayjs'
import 'dayjs/locale/zh-cn'

// AntDV 默认英文：分页器会显示"10 / page"、空表格显示"No Data"、表格右上角是英文提示。
// 本项目界面全中文，这里统一挂中文语言包（顺带把 dayjs 切到中文）。
dayjs.locale('zh-cn')
</script>
