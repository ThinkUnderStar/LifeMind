import { createApp } from 'vue'
import App from './App.vue'
import './styles/tokens.css'
import './styles/base.css'
import { initTheme } from './core/theme'

// 挂载前先定主题，避免首屏闪一下默认色
initTheme()

createApp(App).mount('#app')
