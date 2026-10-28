<template>
  <a-layout class="app-shell">
    <!--
      窄屏（笔记本录屏）时侧栏自动折叠，避免挤掉内容区。
      `v-model:collapsed` 必须有：品牌区要按折叠状态换标记，而 `breakpoint` 触发的
      响应式折叠只发生在 Sider 内部 —— 不绑的话布局层根本不知道现在是收起的。
    -->
    <a-layout-sider
      v-model:collapsed="collapsed"
      class="app-sider"
      theme="light"
      :width="240"
      breakpoint="lg"
      collapsible
      :collapsed-width="64"
    >
      <div class="sider-inner">
        <!--
          品牌区：SVG 标记常在，文字仅在展开时出现。
          折叠到 64px 时不再渲染文字，避免汉字被从中间裁开。
        -->
        <div class="logo" :title="runtimeConfig().title">
          <img class="logo-mark" :src="logoUrl" :alt="runtimeConfig().title" width="34" height="34" />
          <span v-if="!collapsed" class="logo-text">{{ runtimeConfig().title === '智能代码分析工具' ? '智能代码分析' : runtimeConfig().title }}</span>
        </div>
        <a-menu theme="light" mode="inline" :selected-keys="selectedKeys">
          <a-menu-item-group v-for="group in menuGroups" :key="group.key">
            <template #title>
              <span class="nav-label">{{ group.label }}</span>
            </template>
            <a-menu-item v-for="item in group.items" :key="item.path" :title="item.title">
              <template #icon>
                <component :is="item.icon" v-if="item.icon" />
              </template>
              <router-link :to="item.path">{{ item.title }}</router-link>
            </a-menu-item>
          </a-menu-item-group>
        </a-menu>
<!--        <div v-if="!collapsed" class="sider-foot">-->
<!--          <strong>专项与 Agent 工作台</strong>-->
<!--          <span>固定版本 · 只读调查</span>-->
<!--        </div>-->
      </div>
    </a-layout-sider>
    <a-layout class="app-main">
      <a-layout-header class="header">
        <nav class="breadcrumbs" aria-label="面包屑">
          <span class="crumb-root">工作空间</span>
          <span class="sep">/</span>
          <template v-if="parentCrumb">
            <router-link :to="parentCrumb.path">{{ parentCrumb.title }}</router-link>
            <span class="sep">/</span>
          </template>
          <b class="crumb-current">{{ pageTitle }}</b>
        </nav>
      </a-layout-header>
      <a-layout-content class="content">
        <div class="content-inner">
          <router-view v-slot="{ Component, route }">
            <Transition name="soft-switch" mode="out-in">
              <component :is="Component" :key="route.path" />
            </Transition>
          </router-view>
        </div>
      </a-layout-content>
    </a-layout>
  </a-layout>
</template>

<script setup lang="ts">
import { runtimeConfig } from '@/runtime/config'
import logoUrl from '@/assets/system-logo.svg'
import { computed, ref } from 'vue'
import { useRoute } from 'vue-router'
import { activeMenuKey, menuGroups, menuItems } from '@/router'

const route = useRoute()

/** 侧栏是否收起（由 Sider 的折叠按钮或 `breakpoint` 的响应式折叠驱动） */
const collapsed = ref(false)

/** 菜单高亮：详情页 /projects/123 也保持在「项目」上（见 activeMenuKey） */
const selectedKeys = computed(() => {
  const key = activeMenuKey(route.path)
  return key ? [key] : []
})

const pageTitle = computed(() => String(route.meta?.title || ''))

/** 详情和表单页补上可点击的父菜单；列表页本身不再重复一层。 */
const parentCrumb = computed(() => {
  const key = activeMenuKey(route.path)
  if (!key || key === route.path) return null
  return menuItems.find((item) => item.path === key) || null
})
</script>

<style scoped lang="less">
.app-shell {
  min-height: 100vh;
  background: var(--bg);
}

.app-sider {
  position: sticky;
  top: 0;
  align-self: flex-start;
  height: 100vh;
  overflow: hidden;
  background: #fff !important;
  border-right: 1px solid var(--line);
}

.sider-inner {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
  padding: 28px 16px 8px;
}

.logo {
  display: flex;
  flex: none;
  align-items: center;
  gap: 12px;
  min-width: 0;
  min-height: 34px;
  margin: 0 8px 30px;
}

.logo-mark {
  display: block;
  flex: none;
  width: 34px;
  height: 34px;
}

.app-sider.ant-layout-sider-collapsed .sider-inner {
  padding-right: 0;
  padding-left: 0;
}

.app-sider.ant-layout-sider-collapsed .logo {
  justify-content: center;
  margin-right: 0;
  margin-left: 0;
}

.logo-text {
  min-width: 0;
  overflow: hidden;
  color: var(--ink);
  font-size: 19px;
  font-weight: 700;
  letter-spacing: -0.4px;
  white-space: nowrap;
  text-overflow: ellipsis;
}

:deep(.ant-menu) {
  flex: 1;
  min-height: 0;
  overflow: auto;
  border-inline-end: none !important;
  background: transparent;
}

:deep(.ant-menu-item) {
  height: 44px;
  line-height: 44px;
  border-radius: 12px;
  font-weight: 500;
}

:deep(.ant-menu-item-selected) {
  font-weight: 650;
}

:deep(.ant-menu-item-group-title) {
  padding: 16px 12px 10px;
}

.nav-label {
  color: var(--faint);
  font-size: 10px;
  font-weight: 600;
  letter-spacing: 1.7px;
}

.app-sider.ant-layout-sider-collapsed :deep(.ant-menu-item-group-title) {
  display: none;
}

.sider-foot {
  flex: none;
  margin-top: auto;
  padding: 16px;
  border: 1px solid var(--line);
  border-radius: 16px;
  background: var(--bg);
  color: var(--muted);
  font-size: 12px;
  line-height: 1.6;
}

.sider-foot strong {
  display: block;
  color: var(--ink);
  font-weight: 650;
}

.app-main {
  min-width: 0;
  background: var(--bg);
}

.header {
  position: sticky;
  top: 0;
  z-index: 5;
  display: flex;
  align-items: center;
  height: 72px;
  padding: 0 40px;
  line-height: 72px;
  background: #ffffffcc;
  border-bottom: 1px solid var(--line);
  backdrop-filter: blur(8px);
}

.breadcrumbs {
  color: var(--muted);
  font-size: 13px;
}

.breadcrumbs a {
  color: var(--muted);
}

.sep {
  margin: 0 9px;
  color: var(--crumb-sep);
}

.crumb-current {
  color: var(--crumb);
  font-weight: 500;
}

.content {
  background: transparent;
}

.content-inner {
  max-width: 1750px;
  margin: 0 auto;
  padding: 40px 40px 56px;
}

@media (min-width: 1600px) {
  .header {
    padding: 0 45px;
  }

  .content-inner {
    padding-right: 45px;
    padding-left: 45px;
  }
}

@media (max-width: 1100px) {
  .header {
    padding: 0 24px;
  }

  .content-inner {
    padding: 28px 24px;
  }
}

@media (max-width: 760px) {
  .content-inner {
    padding: 20px 14px;
  }

  .header {
    padding: 0 14px;
  }
}
</style>
