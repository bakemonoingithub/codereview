<template>
  <main class="retired-page">
    <div class="retired-card">
      <h1>此页面已停用</h1>
      <p>模型配置和通用规则现在分别维护。你可以继续配置资源，或返回审查中心发起任务。</p>
      <div class="actions">
        <router-link to="/models"><a-button>模型管理</a-button></router-link>
        <router-link to="/prompts"><a-button>提示词管理</a-button></router-link>
        <router-link to="/reviews"><a-button type="primary">审查中心</a-button></router-link>
      </div>
    </div>
  </main>
</template>

<style scoped>
.retired-page { display: grid; min-height: 55vh; place-items: center; padding: 24px; }
.retired-card { max-width: 520px; text-align: center; }
h1 { margin: 0 0 10px; color: var(--ink); font-size: 22px; }
p { margin: 0; color: var(--muted); }
.actions { display: flex; flex-wrap: wrap; justify-content: center; gap: 10px; margin-top: 22px; }
</style>
