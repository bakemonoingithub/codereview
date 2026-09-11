<template>
  <a-layout style="min-height: 100vh">
    <!-- 窄屏（笔记本录屏）时侧栏自动折叠，避免挤掉内容区 -->
    <a-layout-sider :width="200" breakpoint="lg" collapsible :collapsed-width="64">
      <div class="logo">智能代码分析</div>
      <a-menu theme="dark" mode="inline" :selected-keys="selectedKeys">
        <a-menu-item v-for="item in menuItems" :key="item.path">
          <router-link :to="item.path">{{ item.title }}</router-link>
        </a-menu-item>
      </a-menu>
    </a-layout-sider>
    <a-layout>
      <a-layout-header class="header">智能代码分析工具</a-layout-header>
      <a-layout-content class="content">
        <router-view />
      </a-layout-content>
    </a-layout>
  </a-layout>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { activeMenuKey, menuItems } from '@/router'

const route = useRoute()

/** 菜单高亮：详情页 /projects/123 也保持在「项目」上（见 activeMenuKey） */
const selectedKeys = computed(() => {
  const key = activeMenuKey(route.path)
  return key ? [key] : []
})
</script>

<style scoped lang="less">
.logo {
  color: #fff;
  padding: 16px;
  text-align: center;
  font-weight: bold;
  white-space: nowrap;
  overflow: hidden;
}
.header {
  background: #fff;
  padding: 0 16px;
  line-height: 64px;
}
.content {
  margin: 16px;
}
</style>
