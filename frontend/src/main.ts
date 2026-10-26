import { createApp } from 'vue'
import StartupGate from './runtime/StartupGate.vue'
import './styles/tokens.css'
import './styles/global.less'
const gate = createApp(StartupGate, { ready: async () => {
  const { startApplication } = await import('./bootstrap')
  gate.unmount()
  startApplication()
} })
gate.mount('#app')
