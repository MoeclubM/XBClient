import { createRouter, createWebHashHistory } from 'vue-router'
import { store } from './state'
import LoginView from './views/LoginView.vue'
import HomeView from './views/HomeView.vue'
import PlansView from './views/PlansView.vue'
import ProfileView from './views/ProfileView.vue'
import SettingsView from './views/SettingsView.vue'
import AppRulesView from './views/AppRulesView.vue'
import LicensesView from './views/LicensesView.vue'
import TicketsView from './views/TicketsView.vue'

export const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    { path: '/', redirect: () => (store().authData ? '/home' : '/login') },
    { path: '/login', component: LoginView },
    { path: '/home', component: HomeView, meta: { auth: true } },
    { path: '/plans', component: PlansView, meta: { auth: true } },
    { path: '/profile', component: ProfileView, meta: { auth: true } },
    { path: '/tickets', component: TicketsView, meta: { auth: true } },
    { path: '/settings', component: SettingsView, meta: { auth: true } },
    { path: '/settings/app-rules', component: AppRulesView, meta: { auth: true } },
    { path: '/settings/licenses', component: LicensesView, meta: { auth: true, hideNav: true } },
  ],
})

router.beforeEach((to) => {
  if (to.meta.auth && !store().authData) return '/login'
  if (to.path === '/login' && store().authData) return '/home'
  return true
})
