import React from 'react'
import ReactDOM from 'react-dom/client'
import { HashRouter, Routes, Route, Navigate } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { Login } from './pages/Login'
import { Home } from './pages/Home'
import { Plans } from './pages/Plans'
import { Profile } from './pages/Profile'
import { SettingsPage } from './pages/Settings'
import { Licenses } from './pages/Licenses'
import { MainLayout } from './components/MainLayout'
import { useAppStore } from './store'
import { loadSession, loadSettings } from './store/persist'
import { autostartIsEnabled, runtimeCapabilities } from './api/system'
import './styles.css'

const queryClient = new QueryClient()

function RootRedirect() {
  const authData = useAppStore((s) => s.authData)
  return <Navigate to={authData ? '/home' : '/login'} replace />
}

function AuthGuard({ children }: { children: React.ReactNode }) {
  const authData = useAppStore((s) => s.authData)
  if (!authData) return <Navigate to="/login" replace />
  return <>{children}</>
}

async function bootstrap() {
  try {
    const session = await loadSession()
    if (session) useAppStore.getState().setSession(session)
  } catch (error) {
    console.error('load session failed', error)
  }
  try {
    const persisted = await loadSettings()
    if (Object.keys(persisted).length > 0) {
      useAppStore.getState().setSettings(persisted)
    }
    const capabilities = await runtimeCapabilities()
    useAppStore.getState().setCapabilities(capabilities)
    if (!capabilities.system_proxy) {
      useAppStore.getState().setSettings({ autoApplyProxy: false })
    }
    if (capabilities.autostart) {
      const autostart = await autostartIsEnabled()
      useAppStore.getState().setSettings({ autostart })
    } else {
      useAppStore.getState().setSettings({ autostart: false })
    }
  } catch (error) {
    console.error('load settings failed', error)
  }
  ReactDOM.createRoot(document.getElementById('root')!).render(
    <React.StrictMode>
      <QueryClientProvider client={queryClient}>
        <HashRouter>
          <Routes>
            <Route path="/" element={<RootRedirect />} />
            <Route path="/login" element={<Login />} />
            <Route
              element={
                <AuthGuard>
                  <MainLayout />
                </AuthGuard>
              }
            >
              <Route path="/home" element={<Home />} />
              <Route path="/plans" element={<Plans />} />
              <Route path="/profile" element={<Profile />} />
              <Route path="/settings" element={<SettingsPage />} />
              <Route path="/settings/licenses" element={<Licenses />} />
            </Route>
          </Routes>
        </HashRouter>
      </QueryClientProvider>
    </React.StrictMode>,
  )
}

bootstrap()
