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
  androidStartVpn,
  androidStopVpn,
  androidGetVpnState,
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
import { formatTrafficBytes, formatUnixDate, numericValue, publicErrorText } from '../format'
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
    buildConfig,
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
    if (authData && baseUrl) void refresh()
  }, [authData, baseUrl]) // eslint-disable-line react-hooks/exhaustive-deps

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

  // Synchronize Android VPN native state
  useEffect(() => {
    if (capabilities?.platform !== 'android') return

    // 1. Initial state check
    void androidGetVpnState()
      .then((state) => {
        if (state.running && state.nodeIndex >= 0) {
          setVpn({
            sessionId: 0,
            socksAddr: '',
            nodeIndex: state.nodeIndex,
            uploadBytes: 0,
            downloadBytes: 0,
          })
        }
      })
      .catch((err) => setError(publicErrorText(err)))

    // 2. State change subscription
    let unlisten: (() => void) | undefined
    void listen<{ running: boolean; nodeIndex: number; error?: string }>(
      'plugin:xbclient-mobile|vpnStateChanged',
      (event) => {
        const { running, nodeIndex, error } = event.payload
        if (running && nodeIndex >= 0) {
          setVpn({
            sessionId: 0,
            socksAddr: '',
            nodeIndex,
            uploadBytes: 0,
            downloadBytes: 0,
          })
        } else {
          setVpn(null)
          setConnectingIndex(null)
          if (error) {
            setError(error)
          }
        }
      }
    ).then((val) => {
      unlisten = val
    })

    return () => {
      unlisten?.()
    }
  }, [capabilities?.platform, setVpn])

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
      setError(publicErrorText(err))
    } finally {
      setLoading(false)
    }
  }

  async function resolveAerionNode(node: AppNode): Promise<unknown> {
    if (node.protocol === 'direct' || node.protocol === 'block') {
      return JSON.parse(node.rawJson)
    }
    const host = rawNodeHost(node)
    if (!buildConfig?.user_agent) throw new Error('构建配置缺少必要网络标识。')
    const resolvedHost = await resolveNodeHost(settings.nodeDns, host, buildConfig.user_agent)
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
        testError: readableNodeTestError(publicErrorText(err)),
      })
    } finally {
      setSpeedtesting((prev) => ({ ...prev, [index]: false }))
    }
  }

  async function testAllNodes() {
    for (const index of nodes.keys()) {
      await testNode(index)
    }
  }

  async function applySystemProxy(socksAddr: string) {
    if (!settings.autoApplyProxy) return
    if (!capabilities?.system_proxy) {
      throw new Error('当前平台不支持自动系统代理。')
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
    setError('')
    setConnectingIndex(index)

    if (capabilities?.platform === 'android') {
      if (settings.appRuleMode === 'allow' && !settings.allowedApps.trim()) {
        setError(t('app_rules_allow_empty'))
        setConnectingIndex(null)
        return
      }
      try {
        if (vpn) {
          await androidStopVpn()
        }
        const payload = {
          nodeJson: node.rawJson,
          nodesJson: JSON.stringify(nodes.map((item) => JSON.parse(item.rawJson))),
          nodeIndex: index,
          excludedApps: settings.appRuleMode === 'exclude' ? settings.excludedApps : '',
          allowedApps: settings.appRuleMode === 'allow' ? settings.allowedApps : '',
          nodeDns: settings.nodeDns,
          overseasDns: settings.overseasDns,
          directDns: settings.directDns,
          dnsMode: settings.vpnDnsMode,
          virtualDnsPool: settings.virtualDnsPool,
          ipv6Enabled: settings.vpnIpv6Enabled,
        }
        await androidStartVpn(payload)
        setVpn({
          sessionId: 0,
          socksAddr: '',
          nodeIndex: index,
          uploadBytes: 0,
          downloadBytes: 0,
        })
      } catch (err) {
        setError(publicErrorText(err))
      } finally {
        setConnectingIndex(null)
      }
      return
    }

    if (settings.autoApplyProxy && !capabilities?.system_proxy) {
      setError('当前平台不支持自动系统代理。')
      setConnectingIndex(null)
      return
    }

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
      setError(publicErrorText(err))
    } finally {
      setConnectingIndex(null)
    }
  }

  async function disconnect() {
    if (capabilities?.platform === 'android') {
      try {
        await androidStopVpn()
        setVpn(null)
      } catch (err) {
        setError(publicErrorText(err))
      } finally {
        setConnectingIndex(null)
      }
      return
    }

    if (!vpn) return
    try {
      await aerionStop(vpn.sessionId)
    } catch (err) {
      setError(publicErrorText(err))
    } finally {
      setVpn(null)
      try {
        await clearSystemProxy()
      } catch (err) {
        setError(publicErrorText(err))
      }
    }
  }

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
    <main className="md3-screen space-y-5">
      <header className="md3-page-header">
        <span className="md3-page-rail" />
        <div className="min-w-0">
          <h1 className="text-2xl font-semibold tracking-tight text-on-background">{t('nav_nodes')}</h1>
          <p className="mt-1 truncate text-sm text-on-surface-variant">{loading ? t('refreshing') : (email || '未登录')}</p>
        </div>
      </header>

      {error && (
        <p className="rounded-lg border border-rose-500/20 bg-rose-500/10 p-3 text-xs font-semibold text-rose-500 break-words">
          {error}
        </p>
      )}

      {subscription.blockReason && (
        <section className="space-y-3 rounded-xl border border-rose-500/20 bg-rose-500/10 p-4">
          <p className="text-sm font-semibold text-rose-500">
            {subscription.blockReason === 'expired'
              ? '套餐已过期'
              : subscription.blockReason === 'traffic_exceeded'
                ? '流量已用尽'
                : '暂无可用套餐'}
          </p>
          <button
            type="button"
            onClick={() => navigate('/plans')}
            className="rounded-lg bg-rose-500 px-3 py-2 text-xs font-semibold text-white"
          >
            前往套餐
          </button>
        </section>
      )}

      <section className="space-y-2">
        <h2 className="md3-section-title">连接状态</h2>
        <div className="md3-panel space-y-5 text-center">
          <p className={vpn ? 'text-3xl font-semibold text-primary' : 'text-3xl font-semibold text-on-background'}>
            {vpn ? t('status_connected') : t('status_disconnected')}
          </p>

          <button
            type="button"
            onClick={() => void toggleConnection()}
            disabled={isCurrentlyConnecting}
            className={vpn ? 'connection-orb connection-orb--connected mx-auto' : 'connection-orb mx-auto'}
          >
            <span className="relative z-10">
              {isCurrentlyConnecting ? t('action_connecting') : vpn ? t('action_disconnect') : t('action_connect')}
            </span>
          </button>

          {vpn && (
            <dl className="grid grid-cols-2 gap-2 border-t border-outline-variant/30 pt-3 text-center text-xs">
              <div className="md3-info-cell">
                <dt className="text-on-surface-variant">{t('session_duration')}</dt>
                <dd className="mt-1 font-mono font-semibold text-primary">{formatDuration(duration)}</dd>
              </div>
              <div className="md3-info-cell">
                <dt className="text-on-surface-variant">{t('session_traffic')}</dt>
                <dd className="mt-1 font-mono font-semibold text-primary">{formatTrafficBytes(vpn.uploadBytes + vpn.downloadBytes)}</dd>
              </div>
            </dl>
          )}
        </div>
      </section>

      <section className="space-y-2">
        <h2 className="md3-section-title">{t('select_node')}</h2>
        <button
          type="button"
          onClick={() => setNodeSelectOpen(true)}
          className="md3-panel flex min-h-[78px] w-full items-center justify-between gap-3 text-left"
        >
          <div className="min-w-0">
            {selectedNode ? (
              <>
                <p className="truncate text-xl font-semibold text-on-background">{displayNodeName(selectedNode, selectedNodeIndex)}</p>
                <p className="mt-1 truncate text-sm text-on-surface-variant">
                  {selectedNode.protocolLabel}{selectedNode.latencyMs !== undefined ? ` · ${selectedNode.latencyMs} ms` : ''}
                </p>
              </>
            ) : (
              <p className="text-sm text-on-surface-variant">{t('no_nodes')}</p>
            )}
          </div>
          <span className="text-2xl text-on-surface-variant">›</span>
        </button>
      </section>

      {trafficTotal > 0 && (
        <section className="space-y-2">
          <h2 className="md3-section-title">{t('traffic_used')}</h2>
          <div className="md3-panel space-y-3">
            <div className="flex items-center justify-between gap-3 text-sm">
              <span className="text-on-surface-variant">{subscription.summary}</span>
              <span className="font-semibold text-on-background">
                {formatTrafficBytes(trafficUsed)} / {formatTrafficBytes(trafficTotal)}
              </span>
            </div>
            <div className="h-2 overflow-hidden rounded-full bg-surface-high">
              <div className="h-full rounded-full bg-primary" style={{ width: `${progressPercent}%` }} />
            </div>
            <div className="flex items-center justify-between gap-3 text-xs text-on-surface-variant">
              <span>{progressPercent.toFixed(1)}%</span>
              {subscription.expiredAt > 0 && <span>{t('expires_at')}: {formatUnixDate(subscription.expiredAt)}</span>}
            </div>
          </div>
        </section>
      )}

      {nodeSelectOpen && (
        <div className="fixed inset-0 z-50 flex items-end justify-center bg-black/50 p-4 sm:items-center">
          <section className="max-h-[82vh] w-full max-w-lg overflow-hidden rounded-2xl border border-outline-variant/50 bg-surface-low">
            <header className="flex items-center justify-between gap-3 border-b border-outline-variant/30 p-4">
              <div>
                <h2 className="text-base font-semibold text-on-background">{t('select_node')}</h2>
                <p className="mt-1 text-xs text-on-surface-variant">共 {nodes.length} 个</p>
              </div>
              <div className="flex items-center gap-2">
                {nodes.length > 0 && (
                  <button
                    type="button"
                    onClick={() => void testAllNodes()}
                    className="rounded-lg border border-outline-variant/60 px-3 py-1.5 text-xs font-semibold text-primary"
                  >
                    测试全部
                  </button>
                )}
                <button
                  type="button"
                  onClick={() => setNodeSelectOpen(false)}
                  className="rounded-lg border border-outline-variant/60 px-3 py-1.5 text-xs font-semibold text-on-background"
                >
                  关闭
                </button>
              </div>
            </header>

            {nodes.length === 0 ? (
              <p className="p-6 text-center text-xs text-on-surface-variant">{t('no_nodes')}</p>
            ) : (
              <ul className="max-h-[60vh] space-y-2 overflow-y-auto p-3">
                {nodes.map((node, index) => {
                  const isSelected = selectedNodeIndex === index
                  const isConnected = vpn?.nodeIndex === index
                  const isTesting = speedtesting[index] === true

                  return (
                    <li
                      key={index}
                      className={isConnected
                        ? 'flex items-center gap-3 rounded-xl border border-emerald-500/30 bg-emerald-500/10 p-3'
                        : isSelected
                          ? 'flex items-center gap-3 rounded-xl border border-primary/30 bg-primary/10 p-3'
                          : 'flex items-center gap-3 rounded-xl border border-outline-variant/40 bg-surface p-3'}
                    >
                      <button
                        type="button"
                        onClick={() => void chooseNode(index)}
                        className="min-w-0 flex-1 text-left"
                      >
                        <div className="flex items-center gap-2">
                          <p className="truncate text-sm font-semibold text-on-background">{displayNodeName(node, index)}</p>
                          {isConnected && <span className="rounded bg-emerald-500/10 px-1.5 py-0.5 text-[10px] text-emerald-500">已连接</span>}
                          {!isConnected && isSelected && <span className="rounded bg-primary/10 px-1.5 py-0.5 text-[10px] text-primary">已选择</span>}
                        </div>
                        <p className="mt-1 truncate text-xs text-on-surface-variant">
                          {node.protocolLabel}{node.tags.length > 0 ? ` · ${node.tags.join(' · ')}` : ''}
                        </p>
                        {!node.connectSupported && <p className="mt-1 text-[11px] text-amber-500">{t('unsupported_protocol')}</p>}
                        {node.testError && <p className="mt-1 break-all text-[11px] text-rose-500">{node.testError}</p>}
                      </button>

                      <div className="flex shrink-0 items-center gap-2">
                        {node.latencyMs !== undefined && <span className="font-mono text-xs font-semibold text-emerald-500">{node.latencyMs} ms</span>}
                        <button
                          type="button"
                          onClick={() => void testNode(index)}
                          disabled={isTesting}
                          className="rounded-lg border border-outline-variant/60 px-2.5 py-1.5 text-xs font-semibold text-primary disabled:opacity-50"
                        >
                          {isTesting ? '...' : t('node_test')}
                        </button>
                      </div>
                    </li>
                  )
                })}
              </ul>
            )}
          </section>
        </div>
      )}
    </main>
  )
}
