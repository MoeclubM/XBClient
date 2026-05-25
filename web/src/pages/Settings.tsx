import { useEffect, useState } from 'react'
import { getVersion } from '@tauri-apps/api/app'
import { autostartSetEnabled, openInAppBrowser, systemProxyClear, systemProxySet } from '../api/system'
import { xboardRequest } from '../api/xboard'
import { useAppStore, type AppSettings } from '../store'
import { saveSettings } from '../store/persist'
import { enabled } from '../reward'
import { useTranslation } from '../i18n'
import { publicErrorText } from '../format'

interface XboardBody<T = unknown> {
  data?: T
  message?: string
}

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
    baseUrl,
    authData,
    settings,
    capabilities,
    vpn,
    githubProjectUrl,
    setSettings,
    setProfile,
    setAdmobConfig,
  } = useAppStore()
  const systemProxySupported = capabilities?.system_proxy === true
  const autostartSupported = capabilities?.autostart === true
  const vpnSupported = capabilities?.vpn === true
  const mobileControl = capabilities?.admob === true
  const [error, setError] = useState('')
  const [appVersion, setAppVersion] = useState('')


  useEffect(() => {
    void getVersion()
      .then(setAppVersion)
      .catch((err) => setError(publicErrorText(err)))
  }, [])

  useEffect(() => {
    let cancelled = false
    async function loadRewardConfig() {
      if (!authData || !mobileControl) return
      const response = await xboardRequest<XboardBody<Record<string, unknown>>>('admob_reward_config', { baseUrl, authData })
      if (cancelled) return
      if (!response.ok || !response.body?.data) {
        setProfile({ paymentEnabled: false })
        setAdmobConfig({
          admobCloudEnabled: false,
          planRewardAdEnabled: false,
          pointsRewardAdEnabled: false,
          appOpenAdEnabled: false,
          planRewardedAdUnitId: '',
          planRewardSsvUserId: '',
          planRewardSsvCustomData: '',
          pointsRewardedAdUnitId: '',
          pointsRewardSsvUserId: '',
          pointsRewardSsvCustomData: '',
          appOpenAdUnitId: '',
        })
        setError(response.body?.message ?? response.error ?? `HTTP ${response.status}`)
        return
      }
      const data = response.body.data
      const adEnabled = enabled(data.ad_enabled)
      setProfile({ paymentEnabled: enabled(data.payment_enabled) })
      setAdmobConfig({
        admobCloudEnabled: adEnabled,
        planRewardAdEnabled: adEnabled && enabled(data.plan_reward_ad_enabled),
        pointsRewardAdEnabled: adEnabled && enabled(data.points_reward_ad_enabled),
        appOpenAdEnabled: enabled(data.app_open_ad_enabled),
        planRewardedAdUnitId: String(data.plan_rewarded_ad_unit_id ?? ''),
        planRewardSsvUserId: String(data.plan_ssv_user_id ?? ''),
        planRewardSsvCustomData: String(data.plan_ssv_custom_data ?? ''),
        pointsRewardedAdUnitId: String(data.points_rewarded_ad_unit_id ?? ''),
        pointsRewardSsvUserId: String(data.points_ssv_user_id ?? ''),
        pointsRewardSsvCustomData: String(data.points_ssv_custom_data ?? ''),
        appOpenAdUnitId: String(data.app_open_ad_unit_id ?? ''),
        githubProjectUrl: String(data.github_project_url ?? ''),
      })
    }
    void loadRewardConfig().catch((err) => setError(publicErrorText(err)))
    return () => {
      cancelled = true
    }
  }, [authData, baseUrl, mobileControl, setAdmobConfig, setProfile])

  async function persist(patch: Partial<AppSettings>) {
    const next = { ...settings, ...patch }
    setSettings(patch)
    await saveSettings(next).catch((err) => setError(`Save failed: ${publicErrorText(err)}`))
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
      setError(publicErrorText(err))
    }
  }

  async function toggleAutostart(value: boolean) {
    setError('')
    try {
      if (value && !autostartSupported) throw new Error('Not supported')
      await autostartSetEnabled(value)
      await persist({ autostart: value })
    } catch (err) {
      setError(publicErrorText(err))
    }
  }

  return (
    <main className="md3-screen space-y-5">
      <header className="md3-page-header">
        <span className="md3-page-rail" />
        <div className="min-w-0">
          <h1 className="text-2xl font-semibold tracking-tight text-on-background">{t('nav_settings')}</h1>
          <p className="mt-1 text-sm text-on-surface-variant">{t('app_version')}: {appVersion || '-'}</p>
        </div>
      </header>

      {error && (
        <p className="md3-alert md3-alert-error break-words">
          {error}
        </p>
      )}

      <section className="md3-card-low space-y-5">
        {systemProxySupported && (
          <>
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
          </>
        )}

        {autostartSupported && (
          <>
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
          </>
        )}

        {/* Dynamic Theme Changer */}
        <label className="block space-y-1.5">
          <span className="block text-xs font-bold text-on-surface-variant tracking-wider uppercase">
            {t('theme')}
          </span>
          <select
            value={settings.themeMode}
            onChange={(e) => void persist({ themeMode: e.target.value as any })}
            className="md3-field cursor-pointer"
          >
            <option value="system">{t('theme_system')}</option>
            <option value="light">{t('theme_light')}</option>
            <option value="dark">{t('theme_dark')}</option>
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
            className="md3-field cursor-pointer"
          >
            <option value="system">Language: System</option>
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
            className="md3-field font-mono"
            value={settings.nodeDns}
            onChange={(event) => void persist({ nodeDns: event.target.value })}
          />
        </label>

        <hr className="border-t border-outline-variant/20" />

        {vpnSupported && (
          <>
            <label className="block space-y-1.5">
              <span className="block text-xs font-bold text-on-surface-variant tracking-wider uppercase">
                {t('dns_overseas_label')}
              </span>
              <input
                className="md3-field font-mono"
                value={settings.overseasDns}
                onChange={(event) => void persist({ overseasDns: event.target.value })}
              />
            </label>

            <hr className="border-t border-outline-variant/20" />

            <label className="block space-y-1.5">
              <span className="block text-xs font-bold text-on-surface-variant tracking-wider uppercase">
                {t('dns_direct_label')}
              </span>
              <input
                className="md3-field font-mono"
                value={settings.directDns}
                onChange={(event) => void persist({ directDns: event.target.value })}
              />
            </label>

            <hr className="border-t border-outline-variant/20" />

            <label className="block space-y-1.5">
              <span className="block text-xs font-bold text-on-surface-variant tracking-wider uppercase">
                {t('dns_mode')}
              </span>
              <select
                value={settings.vpnDnsMode}
                onChange={(event) => void persist({ vpnDnsMode: event.target.value as AppSettings['vpnDnsMode'] })}
                className="md3-field cursor-pointer"
              >
                <option value="virtual">{t('dns_mode_virtual')}</option>
                <option value="over_tcp">{t('dns_mode_over_tcp')}</option>
                <option value="direct">{t('dns_mode_direct')}</option>
              </select>
            </label>

            <hr className="border-t border-outline-variant/20" />

            <label className="block space-y-1.5">
              <span className="block text-xs font-bold text-on-surface-variant tracking-wider uppercase">
                {t('dns_virtual_pool')}
              </span>
              <input
                className="md3-field font-mono"
                value={settings.virtualDnsPool}
                onChange={(event) => void persist({ virtualDnsPool: event.target.value })}
              />
            </label>

            <hr className="border-t border-outline-variant/20" />

            <label className="flex items-center justify-between gap-4 cursor-pointer">
              <span className="block text-sm font-semibold text-on-background">{t('enable_ipv6')}</span>
              <input
                type="checkbox"
                className="size-4 shrink-0 accent-primary cursor-pointer"
                checked={settings.vpnIpv6Enabled}
                onChange={(event) => void persist({ vpnIpv6Enabled: event.target.checked })}
              />
            </label>

            <hr className="border-t border-outline-variant/20" />
          </>
        )}

        <label className="block space-y-1.5">
          <span className="block text-xs font-bold text-on-surface-variant tracking-wider uppercase">
            {t('node_test_target')}
          </span>
          <input
            className="md3-field font-mono"
            value={settings.nodeTestTarget}
            onChange={(event) => void persist({ nodeTestTarget: event.target.value })}
          />
        </label>

      </section>

      <section className="md3-card-low space-y-4">
        <div className="flex items-center justify-between gap-3">
          <div>
            <h2 className="md3-section-title">{t('about')}</h2>
            <p className="mt-1 text-xs text-on-surface-variant">
              {t('app_version')}: <span className="font-mono">{appVersion || '-'}</span>
            </p>
          </div>
          <a
            className="md3-button md3-button-tonal text-xs"
            href="#/settings/licenses"
          >
            {t('licenses')}
          </a>
        </div>
        {githubProjectUrl && (
          <button
            type="button"
            onClick={() => void openInAppBrowser(githubProjectUrl, t('source_code'))}
            className="md3-button md3-button-outlined h-auto w-full justify-start py-2 text-left text-xs"
          >
            {t('source_code')}: <span className="font-mono break-all">{githubProjectUrl}</span>
          </button>
        )}
      </section>
    </main>
  )
}
