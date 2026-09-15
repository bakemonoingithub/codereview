<template>
  <a-layout style="min-height: 100vh">
    <!--
      窄屏（笔记本录屏）时侧栏自动折叠，避免挤掉内容区。
      `v-model:collapsed` 必须有：品牌区要按折叠状态换标记，而 `breakpoint` 触发的
      响应式折叠只发生在 Sider 内部 —— 不绑的话布局层根本不知道现在是收起的。
    -->
    <a-layout-sider
      v-model:collapsed="collapsed"
      :width="200"
      breakpoint="lg"
      collapsible
      :collapsed-width="64"
    >
      <!--
        品牌区：logo 常在，文字仅在展开时出现。
        原先这里是纯文字 + `overflow: hidden`，侧栏收到 64px 时文字被**从中间硬裁**，
        界面上就是"半截汉字"。
      -->
      <div class="logo">
        <img class="logo-mark" src="/favicon.svg" alt="智能代码分析" />
        <span v-if="!collapsed" class="logo-text">智能代码分析</span>
      </div>
      <a-menu theme="dark" mode="inline" :selected-keys="selectedKeys">
        <a-menu-item v-for="item in menuItems" :key="item.path" :title="item.title">
          <template #icon>
            <component :is="item.icon" v-if="item.icon" />
          </template>
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
import { computed, ref } from 'vue'
import { useRoute } from 'vue-router'
import { activeMenuKey, menuItems } from '@/router'

const route = useRoute()

/** 侧栏是否收起（由 Sider 的折叠按钮或 `breakpoint` 的响应式折叠驱动） */
const collapsed = ref(false)

/** 菜单高亮：详情页 /projects/123 也保持在「项目」上（见 activeMenuKey） */
const selectedKeys = computed(() => {
  const key = activeMenuKey(route.path)
  return key ? [key] : []
})
</script>

<style scoped lang="less">
.logo {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  height: 56px;
  color: #fff;
  font-weight: bold;
  overflow: hidden;
}
.logo-mark {
  flex: none;
  width: 28px;
  height: 28px;
}
/* 文字用省略号而不是硬裁：即便将来侧栏宽度异常，也不会再出现半个汉字 */
.logo-text {
  min-width: 0;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
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
