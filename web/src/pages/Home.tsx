import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import {
  aerionStartSocks,
  aerionStop,
  aerionTestNode,
  subscriptionFetch,
  xboardRequest,
} from '../api/xboard'
import {
  autostartSetEnabled,
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
import { useAppStore, type AppNode, type AppSettings } from '../store'
import { clearSession, saveSettings } from '../store/persist'
import { formatTrafficBytes, formatUnixDate, numericValue } from '../format'

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
  const {
    baseUrl,
    authData,
    email,
    subscribeUrl,
    nodes,
    vpn,
    settings,
    capabilities,
    setSubscribe,
    setNodeResult,
    setVpn,
    setSettings,
    setSubscriptionState,
    reset,
  } = useAppStore()
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const [connectingIndex, setConnectingIndex] = useState<number | null>(null)
  const [settingsOpen, setSettingsOpen] = useState(false)

  useEffect(() => {
    if (!authData) navigate('/login', { replace: true })
  }, [authData, navigate])

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
    } catch (error) {
      setError(error instanceof Error ? error.message : String(error))
    } finally {
      setLoading(false)
    }
  }

  async function resolveAerionNode(node: AppNode): Promise<unknown> {
    const host = rawNodeHost(node)
    const resolvedHost = await resolveNodeHost(settings.nodeDns, host)
    return aerionNodeWithResolvedHost(node, resolvedHost)
  }

  async function testNode(index: number) {
    const node = nodes[index]
    if (!node) return
    setNodeResult(index, { latencyMs: undefined, testError: undefined })
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
    } catch (error) {
      setNodeResult(index, {
        testError: readableNodeTestError(error instanceof Error ? error.message : String(error)),
      })
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
      setError(`当前内核暂不支持 ${node.protocolLabel} 节点。`)
      return
    }
    setError('')
    setConnectingIndex(index)
    try {
      if (vpn) {
        await aerionStop(vpn.sessionId)
      }
      const handle = await aerionStartSocks(await resolveAerionNode(node))
      setVpn({ sessionId: handle.session_id, socksAddr: handle.socks_addr, nodeIndex: index })
      await applySystemProxy(handle.socks_addr)
    } catch (error) {
      setError(error instanceof Error ? error.message : String(error))
    } finally {
      setConnectingIndex(null)
    }
  }

  async function disconnect() {
    if (!vpn) return
    try {
      await aerionStop(vpn.sessionId)
    } catch (error) {
      setError(error instanceof Error ? error.message : String(error))
    } finally {
      setVpn(null)
      try {
        await clearSystemProxy()
      } catch (error) {
        setError(error instanceof Error ? error.message : String(error))
      }
    }
  }

  async function logout() {
    try {
      if (vpn) {
        await aerionStop(vpn.sessionId)
      }
      await clearSystemProxy()
      await clearSession()
      reset()
      navigate('/login')
    } catch (error) {
      setError(error instanceof Error ? error.message : String(error))
    }
  }

  async function persistSettings(patch: Partial<AppSettings>) {
    const next = { ...settings, ...patch }
    setSettings(patch)
    await saveSettings(next).catch((error) => setError(`设置保存失败：${error}`))
  }

  async function toggleAutoApplyProxy(value: boolean) {
    try {
      if (value && !capabilities?.system_proxy) {
        setError('当前平台不支持系统代理接管，请手动配置 SOCKS。')
        return
      }
      await persistSettings({ autoApplyProxy: value })
      if (vpn) {
        if (value) await applySystemProxy(vpn.socksAddr)
        else await clearSystemProxy()
      }
    } catch (error) {
      setError(error instanceof Error ? error.message : String(error))
    }
  }

  async function toggleAutostart(value: boolean) {
    try {
      if (value && !capabilities?.autostart) {
        setError('当前平台不支持开机自启。')
        return
      }
      await autostartSetEnabled(value)
      await persistSettings({ autostart: value })
    } catch (error) {
      setError(error instanceof Error ? error.message : String(error))
    }
  }

  const activeNode = vpn ? nodes[vpn.nodeIndex] : null
  const platform = capabilities?.platform ?? 'unknown'
  const systemProxyEnabled = capabilities?.system_proxy === true
  const autostartEnabled = capabilities?.autostart === true

  return (
    <main className="mx-auto max-w-3xl p-6 space-y-4">
      <header className="flex items-center justify-between">
        <div>
          <h1 className="text-xl font-semibold">XBClient · {email}</h1>
          <p className="text-xs text-slate-400 break-all">{baseUrl}</p>
          <p className="text-xs text-slate-500">平台：{platform} · Tauri 多平台版</p>
        </div>
        <div className="flex gap-2">
          <button
            onClick={() => setSettingsOpen((v) => !v)}
            className="rounded-lg bg-slate-700 px-3 py-2 text-sm hover:bg-slate-600"
          >
            设置
          </button>
          <button
            onClick={refresh}
            disabled={loading}
            className="rounded-lg bg-sky-500 px-3 py-2 text-sm hover:bg-sky-400 disabled:opacity-50"
          >
            {loading ? '刷新中…' : '刷新订阅'}
          </button>
          <button
            onClick={logout}
            className="rounded-lg bg-slate-700 px-3 py-2 text-sm hover:bg-slate-600"
          >
            退出
          </button>
        </div>
      </header>
      {settingsOpen && (
        <section className="space-y-3 rounded-xl bg-slate-900/60 p-4 ring-1 ring-white/10">
          <label className="flex items-center justify-between gap-3">
            <span>
              <span className="block text-sm">自动接管系统代理</span>
              <span className="block text-xs text-slate-400">
                Windows 写入 socks=127.0.0.1:端口；Android 保留本地 SOCKS 手动配置
              </span>
            </span>
            <input
              type="checkbox"
              className="size-4 accent-sky-500 disabled:opacity-50"
              checked={settings.autoApplyProxy && systemProxyEnabled}
              disabled={!systemProxyEnabled}
              onChange={(e) => toggleAutoApplyProxy(e.target.checked)}
            />
          </label>
          <label className="flex items-center justify-between gap-3">
            <span>
              <span className="block text-sm">开机自启</span>
              <span className="block text-xs text-slate-400">桌面端登录用户启动时自动运行 XBClient</span>
            </span>
            <input
              type="checkbox"
              className="size-4 accent-sky-500 disabled:opacity-50"
              checked={settings.autostart && autostartEnabled}
              disabled={!autostartEnabled}
              onChange={(e) => toggleAutostart(e.target.checked)}
            />
          </label>
          <label className="block space-y-1">
            <span className="text-sm">节点 DNS</span>
            <input
              className="w-full rounded-lg bg-slate-800 px-3 py-2 text-sm outline-none ring-1 ring-white/10 focus:ring-sky-500"
              value={settings.nodeDns}
              onChange={(e) => void persistSettings({ nodeDns: e.target.value })}
            />
          </label>
          <label className="block space-y-1">
            <span className="text-sm">测速目标</span>
            <input
              className="w-full rounded-lg bg-slate-800 px-3 py-2 text-sm outline-none ring-1 ring-white/10 focus:ring-sky-500"
              value={settings.nodeTestTarget}
              onChange={(e) => void persistSettings({ nodeTestTarget: e.target.value })}
            />
          </label>
          {capabilities?.tray ? (
            <p className="text-xs text-slate-500">关闭主窗口将最小化到托盘，从托盘菜单退出。</p>
          ) : (
            <p className="text-xs text-slate-500">当前平台无托盘；关闭窗口按系统默认行为处理。</p>
          )}
        </section>
      )}
      {subscribeUrl && <p className="text-xs text-slate-500 break-all">订阅：{subscribeUrl}</p>}
      {vpn ? (
        <section className="rounded-xl bg-emerald-900/30 p-3 ring-1 ring-emerald-500/30">
          <div className="flex items-center justify-between gap-2">
            <div className="min-w-0 text-sm">
              <p className="font-medium text-emerald-300">
                已连接 · {activeNode ? displayNodeName(activeNode, vpn.nodeIndex) : `Node #${vpn.nodeIndex + 1}`}
              </p>
              <p className="text-xs text-emerald-200/80 break-all">
                本地 SOCKS：socks5://{vpn.socksAddr}
              </p>
              {settings.autoApplyProxy && systemProxyEnabled ? (
                <p className="text-xs text-emerald-200/60">系统代理已自动接管。</p>
              ) : (
                <p className="text-xs text-emerald-200/60">
                  系统代理未自动接管，需在系统/浏览器手动配置。
                </p>
              )}
            </div>
            <button
              onClick={disconnect}
              className="shrink-0 rounded-lg bg-rose-500 px-3 py-2 text-sm hover:bg-rose-400"
            >
              断开
            </button>
          </div>
        </section>
      ) : (
        <p className="text-xs text-slate-500">未连接。点击节点右侧"连接"按钮启动本地 SOCKS。</p>
      )}
      {notice && <p className="text-sm text-amber-300">{notice}</p>}
      {error && <p className="text-sm text-rose-400">{error}</p>}
      <ul className="space-y-2">
        {nodes.map((node, index) => {
          const isConnected = vpn?.nodeIndex === index
          const isConnecting = connectingIndex === index
          return (
            <li
              key={index}
              className={`flex items-center justify-between gap-3 rounded-xl p-3 ring-1 ${
                isConnected
                  ? 'bg-emerald-900/30 ring-emerald-500/40'
                  : 'bg-slate-900/60 ring-white/10'
              }`}
            >
              <div className="min-w-0">
                <p className="truncate font-medium">{displayNodeName(node, index)}</p>
                <p className="flex flex-wrap items-center gap-1 text-xs text-slate-400">
                  <span>{node.protocolLabel}</span>
                  {node.tags.map((tag) => (
                    <span
                      key={tag}
                      className="rounded-full bg-slate-800 px-1.5 py-0.5 text-[11px] text-slate-300 ring-1 ring-white/10"
                    >
                      {tag}
                    </span>
                  ))}
                  <span>· {node.host}:{node.port}</span>
                </p>
                {!node.connectSupported && (
                  <p className="text-xs text-amber-300">当前内核暂不支持该节点类型。</p>
                )}
                {node.testError && <p className="text-xs text-rose-400">{node.testError}</p>}
              </div>
              <div className="flex items-center gap-3">
                {node.latencyMs !== undefined && (
                  <span className="text-sm text-emerald-400">{node.latencyMs} ms</span>
                )}
                <button
                  onClick={() => testNode(index)}
                  className="rounded-lg bg-slate-700 px-3 py-1 text-sm hover:bg-slate-600"
                >
                  测速
                </button>
                <button
                  onClick={() => connect(index)}
                  disabled={isConnecting || !node.connectSupported}
                  className={`rounded-lg px-3 py-1 text-sm disabled:opacity-50 ${
                    isConnected
                      ? 'bg-emerald-500 hover:bg-emerald-400'
                      : 'bg-sky-500 hover:bg-sky-400'
                  }`}
                >
                  {isConnecting ? '连接中…' : isConnected ? '已连接' : '连接'}
                </button>
              </div>
            </li>
          )
        })}
      </ul>
      {nodes.length === 0 && !loading && <p className="text-sm text-slate-400">点击"刷新订阅"加载节点。</p>}
    </main>
  )
}
