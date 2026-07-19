import { createApp } from 'vue'
import App from './vue/App.vue'
import { router } from './vue/router'
import { vuetify } from './vue/plugins/vuetify'
import './styles.css'

createApp(App)
  .use(router)
  .use(vuetify)
  .mount('#app')
