import React, { useEffect, useState } from 'react'
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
import { autostartIsEnabled, runtimeCapabilities, runtimeConfig, showAppOpenAd } from './api/system'
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

function LoadingScreen() {
  return (
    <main className="flex min-h-full items-center justify-center p-6">
      <div className="flex flex-col items-center gap-4 text-center">
        <img
          className="h-20 w-20"
          src="./logo.png"
          alt="Logo"
        />
      </div>
    </main>
  )
}

async function loadBootstrapState() {
  const config = await runtimeConfig()
  useAppStore.getState().setBuildConfig(config)
  try {
    const session = await loadSession()
    if (session) useAppStore.getState().setSession({ ...session, baseUrl: config.default_api_url })
  } catch (error) {
    console.error('load session failed', error)
  }
  await loadSettingsAndCapabilities()
}

async function loadSettingsAndCapabilities() {
  try {
    const persisted = await loadSettings()
    if (Object.keys(persisted).length > 0) {
      useAppStore.getState().setSettings(persisted)
    }
    const capabilities = await runtimeCapabilities()
    useAppStore.getState().setCapabilities(capabilities)
    useAppStore.getState().setProfile({ paymentEnabled: true })
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
}

const bootstrapPromise = loadBootstrapState()

function App() {
  const [ready, setReady] = useState(false)
  const [bootstrapError, setBootstrapError] = useState('')
  const themeMode = useAppStore((s) => s.settings.themeMode)
  const capabilities = useAppStore((s) => s.capabilities)
  const appOpenAdEnabled = useAppStore((s) => s.appOpenAdEnabled)
  const appOpenAdUnitId = useAppStore((s) => s.appOpenAdUnitId)

  useEffect(() => {
    bootstrapPromise
      .catch((error) => setBootstrapError(error instanceof Error ? error.message : String(error)))
      .finally(() => setReady(true))
  }, [])

  useEffect(() => {
    const preventGesture = (event: Event) => event.preventDefault()
    const preventWheelZoom = (event: WheelEvent) => {
      if (event.ctrlKey) event.preventDefault()
    }
    const preventKeyZoom = (event: KeyboardEvent) => {
      if ((event.ctrlKey || event.metaKey) && ['+', '-', '=', '0'].includes(event.key)) event.preventDefault()
    }
    document.addEventListener('gesturestart', preventGesture)
    document.addEventListener('gesturechange', preventGesture)
    window.addEventListener('wheel', preventWheelZoom, { passive: false })
    window.addEventListener('keydown', preventKeyZoom)
    return () => {
      document.removeEventListener('gesturestart', preventGesture)
      document.removeEventListener('gesturechange', preventGesture)
      window.removeEventListener('wheel', preventWheelZoom)
      window.removeEventListener('keydown', preventKeyZoom)
    }
  }, [])

  useEffect(() => {
    const el = document.documentElement
    if (themeMode === 'light' || themeMode === 'dark') {
      el.setAttribute('data-theme', themeMode)
    } else {
      el.removeAttribute('data-theme')
    }
  }, [themeMode])

  useEffect(() => {
    if (capabilities?.admob && appOpenAdEnabled && appOpenAdUnitId) {
      void showAppOpenAd(appOpenAdUnitId).catch((error) => console.error('show app open ad failed', error))
    }
  }, [capabilities?.admob, appOpenAdEnabled, appOpenAdUnitId])

  if (!ready) return <LoadingScreen />
  if (bootstrapError) {
    return (
      <main className="flex min-h-full items-center justify-center p-6">
        <p className="max-w-xl rounded-2xl border border-rose-500/20 bg-rose-500/10 p-4 text-sm font-semibold text-rose-500">
          构建配置缺失：{bootstrapError}
        </p>
      </main>
    )
  }

  return (
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
  )
}

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>,
)
