<template>
  <div class="login-wrap">
    <a-card title="登录" class="login-card">
      <a-form layout="vertical">
        <a-form-item label="用户名">
          <a-input v-model:value="username" placeholder="用户名" />
        </a-form-item>
        <a-form-item label="密码">
          <a-input-password v-model:value="password" placeholder="密码" />
        </a-form-item>
        <a-button type="primary" block @click="onLogin">登录</a-button>
      </a-form>
    </a-card>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'

const username = ref('')
const password = ref('')

// TODO: M1 接入 /api/auth/login
const onLogin = () => {
  console.log(username.value, password.value)
}
</script>

<style scoped lang="less">
.login-wrap {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 100vh;
  background: #f0f2f5;
}
.login-card {
  width: 360px;
}
</style>
