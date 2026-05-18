import { useState } from 'react'
import { autostartSetEnabled, systemProxyClear, systemProxySet } from '../api/system'
import { useAppStore, type AppSettings } from '../store'
import { saveSettings } from '../store/persist'

function parseSocksAddr(addr: string): { host: string; port: number } {
  const idx = addr.lastIndexOf(':')
  if (idx <= 0) throw new Error(`SOCKS 地址无效：${addr}`)
  const port = Number(addr.slice(idx + 1))
  if (!Number.isFinite(port) || port <= 0) throw new Error(`SOCKS 端口无效：${addr}`)
  return { host: addr.slice(0, idx), port }
}

export function SettingsPage() {
  const {
    settings,
    capabilities,
    vpn,
    admobCloudEnabled,
    appOpenAdEnabled,
    paymentEnabled,
    planRewardAdEnabled,
    pointsRewardAdEnabled,
    setSettings,
  } = useAppStore()
  const systemProxySupported = capabilities?.system_proxy === true
  const autostartSupported = capabilities?.autostart === true
  const [error, setError] = useState('')

  async function persist(patch: Partial<AppSettings>) {
    const next = { ...settings, ...patch }
    setSettings(patch)
    await saveSettings(next)
  }

  async function toggleSystemProxy(value: boolean) {
    setError('')
    try {
      if (value && !systemProxySupported) throw new Error('当前平台不支持系统代理接管。')
      await persist({ autoApplyProxy: value })
      if (!vpn) return
      if (value) {
        const { host, port } = parseSocksAddr(vpn.socksAddr)
        await systemProxySet(host, port)
      } else if (systemProxySupported) {
        await systemProxyClear()
      }
    } catch (error) {
      setError(error instanceof Error ? error.message : String(error))
    }
  }

  async function toggleAutostart(value: boolean) {
    setError('')
    try {
      if (value && !autostartSupported) throw new Error('当前平台不支持开机自启。')
      await autostartSetEnabled(value)
      await persist({ autostart: value })
    } catch (error) {
      setError(error instanceof Error ? error.message : String(error))
    }
  }

  return (
    <main className="mx-auto max-w-3xl space-y-4 p-6">
      <header>
        <h1 className="text-xl font-semibold">设置</h1>
        <p className="text-xs text-slate-400">Win 优先系统代理，Android 使用本地 SOCKS 手动接管。</p>
      </header>
      {error && <p className="text-sm text-rose-400">{error}</p>}
      <section className="space-y-4 rounded-2xl bg-slate-900/60 p-5 ring-1 ring-white/10">
        <label className="flex items-center justify-between gap-3">
          <span>
            <span className="block text-sm">自动接管系统代理</span>
            <span className="block text-xs text-slate-400">Windows 写入系统 SOCKS；Android/iOS 暂无系统代理写入。</span>
          </span>
          <input
            type="checkbox"
            className="size-4 accent-sky-500 disabled:opacity-50"
            checked={settings.autoApplyProxy && systemProxySupported}
            disabled={!systemProxySupported}
            onChange={(event) => void toggleSystemProxy(event.target.checked)}
          />
        </label>
        <label className="flex items-center justify-between gap-3">
          <span>
            <span className="block text-sm">开机自启</span>
            <span className="block text-xs text-slate-400">仅桌面端支持。</span>
          </span>
          <input
            type="checkbox"
            className="size-4 accent-sky-500 disabled:opacity-50"
            checked={settings.autostart && autostartSupported}
            disabled={!autostartSupported}
            onChange={(event) => void toggleAutostart(event.target.checked)}
          />
        </label>
        <label className="block space-y-1">
          <span className="text-sm">节点 DNS</span>
          <input
            className="w-full rounded-lg bg-slate-800 px-3 py-2 text-sm outline-none ring-1 ring-white/10 focus:ring-sky-500"
            value={settings.nodeDns}
            onChange={(event) => void persist({ nodeDns: event.target.value })}
          />
        </label>
        <label className="block space-y-1">
          <span className="text-sm">测速目标</span>
          <input
            className="w-full rounded-lg bg-slate-800 px-3 py-2 text-sm outline-none ring-1 ring-white/10 focus:ring-sky-500"
            value={settings.nodeTestTarget}
            onChange={(event) => void persist({ nodeTestTarget: event.target.value })}
          />
        </label>
      </section>
      <section className="rounded-2xl bg-slate-900/60 p-5 text-sm ring-1 ring-white/10">
        {capabilities?.admob ? (
          <>
            <p>AdMob：{admobCloudEnabled ? '云控已开启' : '云控未开启'}</p>
            <p className="mt-1 text-xs text-slate-400">
              激励套餐 {planRewardAdEnabled ? '开启' : '关闭'} · 激励积分 {pointsRewardAdEnabled ? '开启' : '关闭'} · 开屏广告 {appOpenAdEnabled ? '开启' : '关闭'}
            </p>
            <p className="mt-1 text-xs text-slate-400">支付：{paymentEnabled ? '云控已开启' : '云控未开启'}</p>
          </>
        ) : (
          <>
            <p>支付入口：始终开启</p>
            <p className="mt-1 text-xs text-slate-400">当前平台不接入广告，也不受广告云控支付限制。</p>
          </>
        )}
        <a className="mt-3 inline-block text-sky-300 hover:text-sky-200" href="#/settings/licenses">
          开源许可
        </a>
      </section>
    </main>
  )
}
