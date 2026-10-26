import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router'
import 'ant-design-vue/dist/reset.css'
import './styles/tokens.css'
import './styles/global.less'

export function startApplication() {
  const app = createApp(App)
  app.use(createPinia())
  app.use(router)
  app.mount('#app')
}
