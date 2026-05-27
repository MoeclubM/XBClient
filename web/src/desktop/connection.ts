import {
  aerionStartSocks,
  aerionStartVpn,
  aerionStop,
  aerionStopVpn,
} from '../api/xboard'
import {
  parseSocksAddr,
  resolveNodeHost,
  systemProxyClear,
  systemProxySet,
} from '../api/system'
import {
  aerionNodeWithResolvedHost,
  dnsAddressForVpn,
  rawNodeHost,
} from '../nodes'
import { publicErrorText } from '../format'
import { reportVpnSession } from '../platform/electron'
import { isDesktopShell } from '../platform/shell'
import { useAppStore, type AppNode } from '../store'

let syncToken = 0
let syncing = false

export function isDesktopConnectionShell(): boolean {
  return isDesktopShell() && Boolean(useAppStore.getState().capabilities?.vpn)
}

async function resolvedNode(node: AppNode): Promise<unknown> {
  const state = useAppStore.getState()
  const host = rawNodeHost(node)
  const resolvedHost = await resolveNodeHost(
    state.settings.nodeDns,
    host,
    state.buildConfig?.user_agent ?? '',
  )
  return aerionNodeWithResolvedHost(node, resolvedHost)
}

async function disconnectSession(): Promise<void> {
  const state = useAppStore.getState()
  const session = state.vpn
  if (!session) return
  const useTun = state.settings.tunEnabled
  if (useTun) await aerionStopVpn(session.sessionId)
  else await aerionStop(session.sessionId)
  if (state.systemProxyActive) {
    await systemProxyClear()
    state.setSystemProxyActive(false)
  }
  state.setVpn(null)
  await reportVpnSession(null)
}

async function startTun(index: number): Promise<void> {
  const state = useAppStore.getState()
  const node = state.nodes[index]
  if (!node?.connectSupported) throw new Error('unsupported_protocol')
  const resolved = await resolvedNode(node)
  const dnsMode = state.settings.vpnDnsMode
  const dns_addr = dnsAddressForVpn(
    dnsMode === 'direct' ? state.settings.directDns : state.settings.overseasDns,
  )
  const handle = await aerionStartVpn({
    node: resolved,
    mtu: 1500,
    dns: dnsMode,
    dns_addr,
    virtual_dns_pool: state.settings.virtualDnsPool,
    ipv6: state.settings.vpnIpv6Enabled,
  })
  state.setPreferredNodeIndex(index)
  state.setVpn({
    sessionId: handle.session_id,
    socksAddr: '',
    nodeIndex: index,
    uploadBytes: 0,
    downloadBytes: 0,
  })
  await reportVpnSession(handle.session_id)
  if (state.systemProxyActive) {
    await systemProxyClear()
    state.setSystemProxyActive(false)
  }
}

async function startSocks(index: number): Promise<void> {
  const state = useAppStore.getState()
  const node = state.nodes[index]
  if (!node?.connectSupported) throw new Error('unsupported_protocol')
  const resolved = await resolvedNode(node)
  const handle = await aerionStartSocks(resolved)
  const parsed = parseSocksAddr(handle.socks_addr)
  state.setPreferredNodeIndex(index)
  state.setVpn({
    sessionId: handle.session_id,
    socksAddr: handle.socks_addr,
    nodeIndex: index,
    uploadBytes: 0,
    downloadBytes: 0,
  })
  if (state.settings.systemProxyEnabled) {
    await systemProxySet(parsed.host, parsed.port)
    state.setSystemProxyActive(true)
  }
}

export async function applyDesktopConnection(): Promise<string | null> {
  if (!isDesktopConnectionShell()) return null
  const state = useAppStore.getState()
  if (state.subscription.blockReason) return null
  const nodeIndex = state.vpn?.nodeIndex ?? state.preferredNodeIndex
  const node = state.nodes[nodeIndex]
  if (!node?.connectSupported) return null

  const token = ++syncToken
  syncing = true
  try {
    if (state.settings.routingMode === 'direct') {
      if (state.vpn) await disconnectSession()
      return null
    }

    const wantTun = state.settings.tunEnabled
    const session = state.vpn
    const tunSession = session && !session.socksAddr
    const socksSession = session && Boolean(session.socksAddr)

    if (wantTun) {
      if (socksSession) await disconnectSession()
      if (!session || session.nodeIndex !== nodeIndex) {
        if (session) await disconnectSession()
        await startTun(nodeIndex)
      }
      return null
    }

    if (tunSession) await disconnectSession()
    if (state.settings.systemProxyEnabled) {
      if (!session || session.nodeIndex !== nodeIndex) {
        if (session) await disconnectSession()
        await startSocks(nodeIndex)
      } else if (!state.systemProxyActive && session.socksAddr) {
        const parsed = parseSocksAddr(session.socksAddr)
        await systemProxySet(parsed.host, parsed.port)
        state.setSystemProxyActive(true)
      }
    } else if (session) {
      await disconnectSession()
    }
    return null
  } catch (err) {
    return publicErrorText(err)
  } finally {
    if (token === syncToken) syncing = false
  }
}

export async function setRoutingMode(mode: 'rule' | 'global' | 'direct'): Promise<string | null> {
  const state = useAppStore.getState()
  state.setSettings({ routingMode: mode })
  return applyDesktopConnection()
}

export async function setTunEnabled(enabled: boolean): Promise<string | null> {
  const state = useAppStore.getState()
  state.setSettings({ tunEnabled: enabled })
  return applyDesktopConnection()
}

export async function setSystemProxyEnabled(enabled: boolean): Promise<string | null> {
  const state = useAppStore.getState()
  state.setSettings({ systemProxyEnabled: enabled })
  return applyDesktopConnection()
}

export function desktopConnectionBusy(): boolean {
  return syncing
}
