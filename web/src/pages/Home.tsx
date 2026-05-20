import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { listen } from '@tauri-apps/api/event'
import {
  aerionStartSocks,
  aerionStop,
  aerionTestNode,
  subscriptionFetch,
  xboardRequest,
} from '../api/xboard'
import {
  resolveNodeHost,
  systemProxyClear,
  systemProxySet,
} from '../api/system'
import {
  DEFAULT_NODE_TEST_TARGET,
  aerionNodeWithResolvedHost,
  displayNodeName,
  mergeNodeLists,
  mergeXboardNodeTags,
  rawNodeHost,
  readableNodeTestError,
  targetHostPort,
  toAppNode,
  type RawNode,
} from '../nodes'
import { useAppStore, type AppNode } from '../store'
import { formatTrafficBytes, formatUnixDate, numericValue } from '../format'
import { useTranslation } from '../i18n'

interface XboardBody {
  data?: unknown
  message?: string
}

function extractRows(value: unknown): RawNode[] {
  if (Array.isArray(value)) return value as RawNode[]
  if (value && typeof value === 'object') {
    const object = value as Record<string, unknown>
    for (const key of ['nodes', 'data', 'list', 'items']) {
      if (Array.isArray(object[key])) return object[key] as RawNode[]
    }
  }
  return []
}

function responseError(response: { status: number; error?: string; body?: XboardBody }): string {
  return response.body?.message || response.error || `HTTP ${response.status}`
}

function subscriptionState(data: Record<string, unknown>) {
  const used = numericValue(data.u) + numericValue(data.d)
  const total = numericValue(data.transfer_enable)
  const plan = data.plan && typeof data.plan === 'object' ? (data.plan as Record<string, unknown>) : null
  const planName = String(plan?.name ?? '')
  const expiredAt = numericValue(data.expired_at)
  const lines = []
  if (planName) lines.push(planName)
  if (total > 0) lines.push(`已用 ${formatTrafficBytes(used)} / ${formatTrafficBytes(total)}`)
  if (expiredAt > 0) lines.push(`到期 ${formatUnixDate(expiredAt)}`)
  const planId = numericValue(data.plan_id)
  const blockReason =
    planId <= 0 && !plan
      ? 'no_plan'
      : expiredAt > 0 && expiredAt <= Date.now() / 1000
        ? 'expired'
        : total <= 0 || used >= total
          ? 'traffic_exceeded'
          : ''
  return {
    summary: lines.join(' · '),
    blockReason: blockReason as '' | 'no_plan' | 'expired' | 'traffic_exceeded',
    trafficUsedBytes: used,
    trafficTotalBytes: total,
    planName,
    expiredAt,
  }
}

function parseSocksAddr(addr: string): { host: string; port: number } {
  const idx = addr.lastIndexOf(':')
  if (idx <= 0) throw new Error(`SOCKS 地址无效：${addr}`)
  const port = Number(addr.slice(idx + 1))
  if (!Number.isFinite(port) || port <= 0) throw new Error(`SOCKS 端口无效：${addr}`)
  return { host: addr.slice(0, idx), port }
}

export function Home() {
  const navigate = useNavigate()
  const t = useTranslation()
  const {
    baseUrl,
    authData,
    email,
    nodes,
    vpn,
    settings,
    capabilities,
    setSubscribe,
    setNodeResult,
    setVpn,
    updateVpnTraffic,
    setSubscriptionState,
    subscription,
  } = useAppStore()
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const [connectingIndex, setConnectingIndex] = useState<number | null>(null)

  // Custom dialog state matching mobile client BottomSheet
  const [selectedNodeIndex, setSelectedNodeIndex] = useState<number>(0)
  const [nodeSelectOpen, setNodeSelectOpen] = useState(false)
  const [speedtesting, setSpeedtesting] = useState<Record<number, boolean>>({})

  // Real-time connection duration timer
  const [duration, setDuration] = useState(0)

  useEffect(() => {
    if (!authData) navigate('/login', { replace: true })
  }, [authData, navigate])

  useEffect(() => {
    let unlisten: (() => void) | undefined
    void listen<string>('aerion-event', (event) => {
      const payload = JSON.parse(event.payload) as {
        type?: string
        wrapper_session_id?: number
        session_id?: number
        upload_bytes?: number
        download_bytes?: number
      }
      if (payload.type === 'traffic_recorded') {
        updateVpnTraffic(
          Number(payload.wrapper_session_id),
          Number(payload.upload_bytes),
          Number(payload.download_bytes),
        )
      }
    }).then((value) => {
      unlisten = value
    })
    return () => {
      unlisten?.()
    }
  }, [updateVpnTraffic])

  // Track active VPN index
  useEffect(() => {
    if (vpn) {
      setSelectedNodeIndex(vpn.nodeIndex)
    }
  }, [vpn])

  // Duration ticking
  useEffect(() => {
    if (!vpn) {
      setDuration(0)
      return
    }
    const start = Date.now()
    const timer = setInterval(() => {
      setDuration(Math.floor((Date.now() - start) / 1000))
    }, 1000)
    return () => clearInterval(timer)
  }, [vpn])

  function formatDuration(sec: number) {
    const h = Math.floor(sec / 3600).toString().padStart(2, '0')
    const m = Math.floor((sec % 3600) / 60).toString().padStart(2, '0')
    const s = (sec % 60).toString().padStart(2, '0')
    return `${h}:${m}:${s}`
  }

  async function refresh() {
    setError('')
    setNotice('')
    setLoading(true)
    try {
      const sub = await xboardRequest<XboardBody>('user_subscribe', {
        baseUrl,
        authData,
      })
      if (!sub.ok) {
        setError(responseError(sub))
        return
      }
      const data = sub.body?.data as Record<string, unknown> | undefined
      const url = data?.subscribe_url
      if (typeof url !== 'string' || !url) {
        setError('订阅响应缺少 subscribe_url')
        return
      }
      setSubscriptionState(subscriptionState(data))

      let list: AppNode[] = []
      const xbclientNodes = await xboardRequest<XboardBody>('xbclient_nodes', {
        baseUrl,
        authData,
      })

      if (xbclientNodes.ok) {
        list = extractRows(xbclientNodes.body?.data).map(toAppNode)
      } else if (xbclientNodes.status === 404) {
        const meta = await subscriptionFetch(url, 'meta')
        if (!meta.ok) {
          setError(meta.error ?? `订阅 HTTP ${meta.status}`)
          return
        }
        const singBox = await subscriptionFetch(url, 'sing-box')
        if (!singBox.ok) {
          setError(singBox.error ?? `订阅 HTTP ${singBox.status}`)
          return
        }
        list = mergeNodeLists(
          (meta.nodes ?? []).map((node) => toAppNode(node as RawNode)),
          (singBox.nodes ?? []).map((node) => toAppNode(node as RawNode)),
        )
        setNotice('XBClient 节点接口不可用，已使用原订阅节点。')
      } else {
        setError(responseError(xbclientNodes))
        return
      }

      if (list.length > 0) {
        const tagRows = await xboardRequest<XboardBody>('nodes', { baseUrl, authData })
        if (!tagRows.ok) {
          setError(`节点标签同步失败：${responseError(tagRows)}`)
          return
        }
        list = mergeXboardNodeTags(list, extractRows(tagRows.body?.data))
      }
      setSubscribe({ subscribeUrl: url, nodes: list })
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err))
    } finally {
      setLoading(false)
    }
  }

  async function resolveAerionNode(node: AppNode): Promise<unknown> {
    const host = rawNodeHost(node)
    const resolvedHost = await resolveNodeHost(settings.nodeDns, host, settings.apiUserAgent)
    return aerionNodeWithResolvedHost(node, resolvedHost)
  }

  async function testNode(index: number) {
    const node = nodes[index]
    if (!node) return
    setNodeResult(index, { latencyMs: undefined, testError: undefined })
    setSpeedtesting((prev) => ({ ...prev, [index]: true }))
    try {
      const target = targetHostPort(settings.nodeTestTarget.trim() || DEFAULT_NODE_TEST_TARGET)
      const result = await aerionTestNode({
        node: await resolveAerionNode(node),
        target_host: target.host,
        target_port: target.port,
        target_tls: target.tls,
      })
      if (result.ok) {
        setNodeResult(index, { latencyMs: result.latency_ms })
      } else {
        setNodeResult(index, { testError: readableNodeTestError(result.error ?? '测速失败') })
      }
    } catch (err) {
      setNodeResult(index, {
        testError: readableNodeTestError(err instanceof Error ? err.message : String(err)),
      })
    } finally {
      setSpeedtesting((prev) => ({ ...prev, [index]: false }))
    }
  }

  async function applySystemProxy(socksAddr: string) {
    if (!settings.autoApplyProxy) return
    if (!capabilities?.system_proxy) {
      throw new Error('当前平台不支持系统代理接管，请手动配置 SOCKS。')
    }
    const { host, port } = parseSocksAddr(socksAddr)
    await systemProxySet(host, port)
  }

  async function clearSystemProxy() {
    if (!settings.autoApplyProxy || !capabilities?.system_proxy) return
    await systemProxyClear()
  }

  async function connect(index: number) {
    const node = nodes[index]
    if (!node) return
    if (!node.connectSupported) {
      setError(t('unsupported_protocol'))
      return
    }
    if (settings.autoApplyProxy && !capabilities?.system_proxy) {
      setError('当前平台不支持系统代理接管，请关闭自动接管后手动配置 SOCKS。')
      return
    }
    setError('')
    setConnectingIndex(index)
    try {
      if (vpn) {
        await aerionStop(vpn.sessionId)
      }
      const handle = await aerionStartSocks(await resolveAerionNode(node))
      try {
        await applySystemProxy(handle.socks_addr)
      } catch (err) {
        await aerionStop(handle.session_id)
        throw err
      }
      setVpn({
        sessionId: handle.session_id,
        socksAddr: handle.socks_addr,
        nodeIndex: index,
        uploadBytes: 0,
        downloadBytes: 0,
      })
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err))
    } finally {
      setConnectingIndex(null)
    }
  }

  async function disconnect() {
    if (!vpn) return
    try {
      await aerionStop(vpn.sessionId)
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err))
    } finally {
      setVpn(null)
      try {
        await clearSystemProxy()
      } catch (err) {
        setError(err instanceof Error ? err.message : String(err))
      }
    }
  }

  // Toggle connection to the *selected* node
  async function toggleConnection() {
    if (vpn) {
      await disconnect()
    } else {
      if (nodes.length === 0) {
        setError(t('no_nodes'))
        return
      }
      await connect(selectedNodeIndex)
    }
  }

  // Choose a node from the Selector Dialog
  async function chooseNode(index: number) {
    setSelectedNodeIndex(index)
    setNodeSelectOpen(false)
    if (vpn) {
      await connect(index)
    }
  }

  const selectedNode = nodes[selectedNodeIndex] || nodes[0]
  const isCurrentlyConnecting = connectingIndex !== null

  // Progress calculations
  const trafficUsed = subscription.trafficUsedBytes
  const trafficTotal = subscription.trafficTotalBytes
  const progressPercent = trafficTotal > 0 ? Math.min(100, (trafficUsed / trafficTotal) * 100) : 0

  return (
    <main className="mx-auto max-w-2xl p-6 space-y-5 pb-24">
      {/* Top Header Row */}
      <header className="flex items-center justify-between border-b border-outline-variant/30 pb-3.5">
        <div className="flex items-center gap-3">
          <img className="h-9 w-9 shrink-0 filter drop-shadow-[0_4px_8px_rgba(11,87,208,0.2)]" src="./logo.svg" alt="Logo" />
          <div className="min-w-0">
            <h1 className="text-base font-bold tracking-tight text-primary break-all">XBClient · {email.split('@')[0]}</h1>
            <p className="text-[10px] text-on-surface-variant font-medium break-all">{baseUrl}</p>
          </div>
        </div>
        <button
          onClick={refresh}
          disabled={loading}
          className="rounded-xl bg-primary px-4 py-2 text-xs font-bold text-white shadow-sm hover:bg-primary/95 hover:shadow active:scale-95 disabled:opacity-40 transition-all flex items-center gap-1.5 cursor-pointer"
        >
          <svg className={`h-3.5 w-3.5 ${loading ? 'animate-spin' : ''}`} fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="3">
            <path strokeLinecap="round" strokeLinejoin="round" d="M4 4v5h.582m15.356 2A8.001 8.001 0 1121.27 15" />
          </svg>
          {loading ? t('refreshing') : t('refresh_sub')}
        </button>
      </header>

      {/* Messages banner */}
      {notice && (
        <p className="rounded-xl bg-amber-500/10 p-3 text-xs font-bold text-amber-500 border border-amber-500/20">
          ⚠️ {notice}
        </p>
      )}
      {error && (
        <p className="rounded-xl bg-rose-500/10 p-3 text-xs font-bold text-rose-500 border border-rose-500/20 break-words">
          ❌ {error}
        </p>
      )}

      {/* Subscription Warnings block */}
      {subscription.blockReason && (
        <section className="rounded-2xl bg-rose-500/10 border border-rose-500/20 p-5 shadow-sm space-y-3">
          <p className="text-sm font-bold text-rose-500">
            {subscription.blockReason === 'expired' && '⚠️ 套餐已过期 / Subscription Expired'}
            {subscription.blockReason === 'traffic_exceeded' && '⚠️ 流量已用尽 / Traffic Exceeded'}
            {subscription.blockReason === 'no_plan' && '⚠️ 暂无可用套餐 / No Active Subscription'}
          </p>
          <button
            onClick={() => navigate('/plans')}
            className="rounded-xl bg-rose-500 px-4 py-2 text-xs font-bold text-white shadow hover:bg-rose-600 active:scale-95 transition-all cursor-pointer"
          >
            🛒 前往获取套餐 / Purchase Plan
          </button>
        </section>
      )}

      {/* Dynamic Connection Control Card */}
      <section className="rounded-3xl bg-surface-low p-6 shadow-md border border-outline-variant/40 flex flex-col items-center justify-center text-center space-y-6 relative overflow-hidden">
        <div className={`absolute -right-10 -top-10 h-36 w-36 rounded-full filter blur-2xl transition-all duration-500 ${vpn ? 'bg-emerald-500/10' : 'bg-primary/5'}`}></div>

        <div className="space-y-1">
          <span className={`inline-flex items-center gap-1.5 rounded-full px-3 py-1 text-xs font-extrabold shadow-sm ${
            vpn
              ? 'bg-emerald-500/15 text-emerald-500 border border-emerald-500/25'
              : 'bg-on-surface-variant/10 text-on-surface-variant border border-outline-variant/20'
          }`}>
            <span className={`h-2 w-2 rounded-full ${vpn ? 'bg-emerald-500 animate-pulse' : 'bg-on-surface-variant/40'}`}></span>
            {vpn ? t('status_connected') : t('status_disconnected')}
          </span>
        </div>

        {/* Big Premium Action Toggle Button */}
        <button
          onClick={toggleConnection}
          disabled={isCurrentlyConnecting}
          className={`h-28 w-28 rounded-full flex flex-col items-center justify-center shadow-lg border-4 transition-all duration-300 transform active:scale-90 cursor-pointer ${
            vpn
              ? 'bg-emerald-500 border-emerald-400 text-white drop-shadow-[0_8px_16px_rgba(16,185,129,0.3)] hover:bg-emerald-400'
              : 'bg-primary border-primary/20 text-white drop-shadow-[0_8px_16px_rgba(11,87,208,0.25)] hover:bg-primary/95'
          } disabled:opacity-40`}
        >
          {isCurrentlyConnecting ? (
            <div className="h-8 w-8 animate-spin rounded-full border-3 border-white border-t-transparent"></div>
          ) : (
            <>
              <svg className="h-8 w-8 mb-1" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2.2">
                <path strokeLinecap="round" strokeLinejoin="round" d="M13 10V3L4 14h7v7l9-11h-7z" />
              </svg>
              <span className="text-xs font-extrabold tracking-wide">
                {vpn ? t('action_disconnect') : t('action_connect')}
              </span>
            </>
          )}
        </button>

        {/* Running stats drawer */}
        {vpn && (
          <div className="grid grid-cols-3 gap-4 w-full pt-4 border-t border-outline-variant/20 max-w-md">
            <div className="rounded-2xl bg-surface p-3 border border-outline-variant/20 text-center">
              <span className="block text-[10px] font-bold text-on-surface-variant tracking-wider uppercase mb-0.5">{t('session_duration')}</span>
              <span className="text-sm font-extrabold text-primary font-mono">{formatDuration(duration)}</span>
            </div>
            <div className="rounded-2xl bg-surface p-3 border border-outline-variant/20 text-center">
              <span className="block text-[10px] font-bold text-on-surface-variant tracking-wider uppercase mb-0.5">{t('session_traffic')}</span>
              <span className="text-sm font-extrabold text-primary font-mono">{formatTrafficBytes(vpn.uploadBytes + vpn.downloadBytes)}</span>
            </div>
            <div className="rounded-2xl bg-surface p-3 border border-outline-variant/20 text-center">
              <span className="block text-[10px] font-bold text-on-surface-variant tracking-wider uppercase mb-0.5">SOCKS Port</span>
              <span className="text-sm font-extrabold text-emerald-500 font-mono">{vpn.socksAddr.split(':')[1]}</span>
            </div>
          </div>
        )}

        {/* Proxy system instructions */}
        <p className="text-[10px] text-on-surface-variant font-semibold">
          {vpn
            ? (settings.autoApplyProxy && capabilities?.system_proxy ? t('running_proxy') : t('manual_proxy'))
            : t('not_connected_desc')}
        </p>
      </section>

      {/* Selected Node Card */}
      {selectedNode ? (
        <section
          onClick={() => setNodeSelectOpen(true)}
          className="rounded-2xl bg-surface-low p-4 shadow-sm border border-outline-variant/40 hover:border-primary/30 transition-all duration-200 cursor-pointer flex items-center justify-between gap-4 group"
        >
          <div className="min-w-0 space-y-1">
            <span className="text-[10px] font-extrabold text-primary tracking-wider uppercase">{t('select_node')}</span>
            <p className="font-extrabold text-base tracking-tight truncate group-hover:text-primary transition-colors">
              {displayNodeName(selectedNode, selectedNodeIndex)}
            </p>
            <p className="flex flex-wrap items-center gap-1.5 text-xs text-on-surface-variant font-semibold">
              <span className="px-1.5 py-0.5 bg-surface rounded-md border border-outline-variant/20 text-[10px] uppercase font-bold text-primary">
                {selectedNode.protocolLabel}
              </span>
              {selectedNode.tags.slice(0, 2).map((tag) => (
                <span key={tag} className="px-1.5 py-0.5 bg-surface rounded-md border border-outline-variant/20 text-[10px] font-bold">
                  {tag}
                </span>
              ))}
              <span>· {selectedNode.host}:{selectedNode.port}</span>
            </p>
          </div>

          <div className="flex items-center gap-2 shrink-0">
            {selectedNode.latencyMs !== undefined && (
              <span className="text-xs font-bold text-emerald-500 font-mono bg-emerald-500/10 px-2 py-1 rounded-lg border border-emerald-500/20">
                {selectedNode.latencyMs} ms
              </span>
            )}
            <div className="h-8 w-8 rounded-full bg-surface-variant flex items-center justify-center text-on-surface-variant group-hover:bg-primary group-hover:text-white transition-all">
              <svg className="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2.5">
                <path strokeLinecap="round" strokeLinejoin="round" d="M9 5l7 7-7 7" />
              </svg>
            </div>
          </div>
        </section>
      ) : (
        <section
          onClick={() => setNodeSelectOpen(true)}
          className="rounded-2xl bg-surface-low p-4 shadow-sm border border-outline-variant/40 hover:border-primary/30 transition-all duration-200 cursor-pointer flex items-center justify-between text-on-surface-variant"
        >
          <span className="text-xs font-bold">{t('no_nodes')}</span>
          <svg className="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2.5">
            <path strokeLinecap="round" strokeLinejoin="round" d="M9 5l7 7-7 7" />
          </svg>
        </section>
      )}

      {/* Subscriber/Traffic Status Card */}
      {trafficTotal > 0 && (
        <section className="rounded-2xl bg-surface-low p-5 shadow-sm border border-outline-variant/40 space-y-3.5">
          <div className="flex items-start justify-between gap-3">
            <div>
              <p className="text-[10px] font-bold text-on-surface-variant tracking-wider uppercase">{t('traffic_used')}</p>
              <p className="text-base font-extrabold text-on-background mt-0.5">
                {formatTrafficBytes(trafficUsed)} / <span className="text-on-surface-variant font-bold text-sm">{formatTrafficBytes(trafficTotal)}</span>
              </p>
            </div>
            {subscription.planName && (
              <span className="rounded-full bg-primary/10 px-2.5 py-1 text-[10px] font-bold text-primary border border-primary/20">
                🏷️ {subscription.planName}
              </span>
            )}
          </div>

          {/* Premium Material Design 3 progress bar */}
          <div className="w-full h-3 rounded-full bg-surface-variant overflow-hidden border border-outline-variant/30 relative">
            <div
              style={{ width: `${progressPercent}%` }}
              className={`h-full rounded-full transition-all duration-500 ${
                progressPercent > 90
                  ? 'bg-rose-500'
                  : progressPercent > 70
                    ? 'bg-amber-500'
                    : 'bg-primary'
              }`}
            ></div>
          </div>

          <div className="flex items-center justify-between text-[10px] font-bold text-on-surface-variant">
            <span>{progressPercent.toFixed(1)}% Used</span>
            {subscription.expiredAt > 0 && (
              <span>📅 {t('expires_at')}: {formatUnixDate(subscription.expiredAt)}</span>
            )}
          </div>
        </section>
      )}

      {/* Select Node Modal Dialog (Replaces long list in index page) */}
      {nodeSelectOpen && (
        <div className="fixed inset-0 z-50 bg-black/60 backdrop-blur-xs flex items-center justify-center p-4 transition-all animate-fade-in">
          <div className="bg-surface-low border border-outline-variant/40 rounded-3xl w-full max-w-lg p-5 flex flex-col max-h-[80vh] shadow-2xl relative animate-slide-up">

            <header className="flex items-center justify-between pb-3.5 border-b border-outline-variant/20 mb-4">
              <div>
                <h2 className="text-base font-extrabold text-on-background tracking-tight">{t('select_node')}</h2>
                <p className="text-[10px] text-on-surface-variant font-medium mt-0.5">全部节点 ({nodes.length})</p>
              </div>
              <button
                onClick={() => setNodeSelectOpen(false)}
                className="h-8 w-8 rounded-full bg-surface-variant flex items-center justify-center text-on-surface-variant hover:bg-rose-500 hover:text-white transition-all cursor-pointer"
              >
                <svg className="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2.5">
                  <path strokeLinecap="round" strokeLinejoin="round" d="M6 18L18 6M6 6l12 12" />
                </svg>
              </button>
            </header>

            {nodes.length === 0 ? (
              <div className="flex-1 flex items-center justify-center text-center p-8">
                <p className="text-xs text-on-surface-variant font-medium">{t('no_nodes')}</p>
              </div>
            ) : (
              <ul className="flex-1 overflow-y-auto space-y-2.5 pr-1.5 min-h-[300px]">
                {nodes.map((node, index) => {
                  const isSelected = selectedNodeIndex === index
                  const isConnected = vpn?.nodeIndex === index
                  const isTesting = speedtesting[index] === true

                  return (
                    <li
                      key={index}
                      className={`flex items-center justify-between gap-3 rounded-2xl p-3.5 border transition-all duration-150 ${
                        isConnected
                          ? 'bg-emerald-500/10 border-emerald-500/35 hover:border-emerald-500/50'
                          : isSelected
                            ? 'bg-primary/15 border-primary/35 hover:border-primary/50'
                            : 'bg-surface border-outline-variant/35 hover:border-primary/25'
                      }`}
                    >
                      {/* Node Details click triggers selection */}
                      <div
                        onClick={() => chooseNode(index)}
                        className="min-w-0 flex-1 cursor-pointer space-y-1"
                      >
                        <p className={`font-bold text-sm truncate tracking-tight ${isConnected ? 'text-emerald-500' : isSelected ? 'text-primary' : 'text-on-background'}`}>
                          {displayNodeName(node, index)}
                        </p>
                        <p className="flex flex-wrap items-center gap-1.5 text-[10px] text-on-surface-variant font-semibold">
                          <span className="px-1.5 py-0.5 bg-surface-low rounded border border-outline-variant/20 uppercase font-bold text-primary">
                            {node.protocolLabel}
                          </span>
                          {node.tags.map((tag) => (
                            <span key={tag} className="px-1.5 py-0.5 bg-surface-low rounded border border-outline-variant/20 font-bold">
                              {tag}
                            </span>
                          ))}
                          <span>· {node.host}:{node.port}</span>
                        </p>
                        {!node.connectSupported && (
                          <p className="text-[10px] text-amber-500 font-bold">⚠️ {t('unsupported_protocol')}</p>
                        )}
                        {node.testError && <p className="text-[10px] text-rose-500 font-medium break-all">{node.testError}</p>}
                      </div>

                      {/* Right-aligned actions: Speedtest and select checkmark */}
                      <div className="flex items-center gap-2 shrink-0">
                        {node.latencyMs !== undefined && (
                          <span className="text-[11px] font-extrabold text-emerald-500 font-mono bg-emerald-500/10 px-2 py-0.5 rounded border border-emerald-500/20">
                            {node.latencyMs} ms
                          </span>
                        )}
                        <button
                          onClick={(e) => {
                            e.stopPropagation()
                            testNode(index)
                          }}
                          disabled={isTesting}
                          className="rounded-xl bg-primary/10 px-3 py-1.5 text-[10px] font-bold text-primary hover:bg-primary/20 active:scale-95 transition-all cursor-pointer border border-primary/20 flex items-center justify-center gap-1"
                        >
                          {isTesting ? (
                            <div className="h-3 w-3 animate-spin rounded-full border border-primary border-t-transparent"></div>
                          ) : (
                            '⚡'
                          )}
                          {t('node_test')}
                        </button>
                      </div>
                    </li>
                  )
                })}
              </ul>
            )}
          </div>
        </div>
      )}
    </main>
  )
}
