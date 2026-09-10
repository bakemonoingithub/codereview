<template>
  <a-layout style="min-height: 100vh">
    <a-layout-sider>
      <div class="logo">智能代码分析</div>
      <a-menu theme="dark" mode="inline" :selected-keys="[route.path]">
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
import { useRoute } from 'vue-router'

const route = useRoute()

const menuItems = [
  { path: '/projects', title: '项目' },
  { path: '/models', title: '模型' },
  { path: '/strategies', title: '策略' },
  { path: '/prompts', title: '提示词' }
]
</script>

<style scoped lang="less">
.logo {
  color: #fff;
  padding: 16px;
  text-align: center;
  font-weight: bold;
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
