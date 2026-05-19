import { useState } from 'react'
import { autostartSetEnabled, systemProxyClear, systemProxySet } from '../api/system'
import { useAppStore, type AppSettings } from '../store'
import { saveSettings } from '../store/persist'
import { useTranslation } from '../i18n'

function parseSocksAddr(addr: string): { host: string; port: number } {
  const idx = addr.lastIndexOf(':')
  if (idx <= 0) throw new Error(`SOCKS 地址无效：${addr}`)
  const port = Number(addr.slice(idx + 1))
  if (!Number.isFinite(port) || port <= 0) throw new Error(`SOCKS 端口无效：${addr}`)
  return { host: addr.slice(0, idx), port }
}

export function SettingsPage() {
  const t = useTranslation()
  const {
    settings,
    capabilities,
    vpn,
    setSettings,
  } = useAppStore()
  const systemProxySupported = capabilities?.system_proxy === true
  const autostartSupported = capabilities?.autostart === true
  const [error, setError] = useState('')

  async function persist(patch: Partial<AppSettings>) {
    const next = { ...settings, ...patch }
    setSettings(patch)
    await saveSettings(next).catch((err) => setError(`Save failed: ${err}`))
  }

  async function toggleSystemProxy(value: boolean) {
    setError('')
    try {
      if (value && !systemProxySupported) throw new Error(t('system_proxy_desc'))
      await persist({ autoApplyProxy: value })
      if (!vpn) return
      if (value) {
        const { host, port } = parseSocksAddr(vpn.socksAddr)
        await systemProxySet(host, port)
      } else if (systemProxySupported) {
        await systemProxyClear()
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err))
    }
  }

  async function toggleAutostart(value: boolean) {
    setError('')
    try {
      if (value && !autostartSupported) throw new Error('Not supported')
      await autostartSetEnabled(value)
      await persist({ autostart: value })
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err))
    }
  }

  return (
    <main className="mx-auto max-w-3xl space-y-5 p-6 pb-24">
      <header className="border-b border-outline-variant/30 pb-3">
        <h1 className="text-xl font-bold tracking-tight text-primary">{t('nav_settings')}</h1>
        <p className="mt-1 text-xs text-on-surface-variant font-medium">
          {t('system_proxy_desc')}
        </p>
      </header>

      {error && (
        <p className="rounded-lg bg-rose-500/10 p-3 text-xs font-semibold text-rose-500 border border-rose-500/20">
          {error}
        </p>
      )}

      {/* Core App Settings Panel */}
      <section className="space-y-5 rounded-2xl bg-surface-low p-5 shadow-sm border border-outline-variant/40">
        <label className="flex items-center justify-between gap-4 cursor-pointer">
          <span className="space-y-0.5">
            <span className="block text-sm font-semibold text-on-background">{t('system_proxy')}</span>
            <span className="block text-xs text-on-surface-variant leading-relaxed">
              {t('system_proxy_desc')}
            </span>
          </span>
          <input
            type="checkbox"
            className="size-4 shrink-0 accent-primary cursor-pointer disabled:opacity-40"
            checked={settings.autoApplyProxy && systemProxySupported}
            disabled={!systemProxySupported}
            onChange={(event) => void toggleSystemProxy(event.target.checked)}
          />
        </label>

        <hr className="border-t border-outline-variant/20" />

        <label className="flex items-center justify-between gap-4 cursor-pointer">
          <span className="space-y-0.5">
            <span className="block text-sm font-semibold text-on-background">{t('autostart')}</span>
            <span className="block text-xs text-on-surface-variant leading-relaxed">
              {t('autostart_desc')}
            </span>
          </span>
          <input
            type="checkbox"
            className="size-4 shrink-0 accent-primary cursor-pointer disabled:opacity-40"
            checked={settings.autostart && autostartSupported}
            disabled={!autostartSupported}
            onChange={(event) => void toggleAutostart(event.target.checked)}
          />
        </label>

        <hr className="border-t border-outline-variant/20" />

        {/* Dynamic Theme Changer */}
        <label className="block space-y-1.5">
          <span className="block text-xs font-bold text-on-surface-variant tracking-wider uppercase">
            {t('theme')}
          </span>
          <select
            value={settings.themeMode}
            onChange={(e) => void persist({ themeMode: e.target.value as any })}
            className="w-full rounded-xl bg-surface px-3 py-2 text-sm outline-none border border-outline-variant/60 focus:border-primary focus:ring-1 focus:ring-primary transition-all cursor-pointer font-medium"
          >
            <option value="system">🎨 {t('theme_system')}</option>
            <option value="light">☀️ {t('theme_light')}</option>
            <option value="dark">🌙 {t('theme_dark')}</option>
          </select>
        </label>

        <hr className="border-t border-outline-variant/20" />

        {/* Dynamic Language Changer */}
        <label className="block space-y-1.5">
          <span className="block text-xs font-bold text-on-surface-variant tracking-wider uppercase">
            {t('language')}
          </span>
          <select
            value={settings.appLanguage}
            onChange={(e) => void persist({ appLanguage: e.target.value as any })}
            className="w-full rounded-xl bg-surface px-3 py-2 text-sm outline-none border border-outline-variant/60 focus:border-primary focus:ring-1 focus:ring-primary transition-all cursor-pointer font-medium"
          >
            <option value="system">🌐 Language: System</option>
            <option value="zh-CN">中文</option>
            <option value="en">English</option>
            <option value="ja">日本語</option>
            <option value="ru">Русский</option>
            <option value="fa">فارسی</option>
          </select>
        </label>

        <hr className="border-t border-outline-variant/20" />

        <label className="block space-y-1.5">
          <span className="block text-xs font-bold text-on-surface-variant tracking-wider uppercase">
            {t('node_dns')}
          </span>
          <input
            className="w-full rounded-xl bg-surface px-3 py-2 text-sm outline-none border border-outline-variant/60 focus:border-primary focus:ring-1 focus:ring-primary transition-all font-mono"
            value={settings.nodeDns}
            onChange={(event) => void persist({ nodeDns: event.target.value })}
          />
        </label>

        <hr className="border-t border-outline-variant/20" />

        <label className="block space-y-1.5">
          <span className="block text-xs font-bold text-on-surface-variant tracking-wider uppercase">
            {t('node_test_target')}
          </span>
          <input
            className="w-full rounded-xl bg-surface px-3 py-2 text-sm outline-none border border-outline-variant/60 focus:border-primary focus:ring-1 focus:ring-primary transition-all font-mono"
            value={settings.nodeTestTarget}
            onChange={(event) => void persist({ nodeTestTarget: event.target.value })}
          />
        </label>
      </section>

      {/* Licenses Link Section */}
      <section className="rounded-2xl bg-surface-low p-5 shadow-sm border border-outline-variant/40 flex items-center justify-between">
        <span className="text-xs text-on-surface-variant font-bold uppercase tracking-wider">
          {t('licenses')}
        </span>
        <a
          className="inline-flex items-center gap-1.5 rounded-xl bg-primary/10 px-4 py-2 text-xs font-bold text-primary hover:bg-primary/20 active:scale-95 transition-all cursor-pointer"
          href="#/settings/licenses"
        >
          📜 {t('licenses')}
        </a>
      </section>
    </main>
  )
}
