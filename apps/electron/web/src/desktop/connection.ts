import {
  aerionStartRoute,
  aerionStartSocks,
  aerionStartVpn,
  aerionStop,
  aerionStopRoute,
  aerionStopVpn,
} from '../api/xboard'
import {
  parseSocksAddr,
  resolveAppNode,
  systemProxyClear,
  systemProxySet,
} from '../api/system'
import {
  dnsAddressForVpn,
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
  if (!state.buildConfig?.user_agent) throw new Error('XBCLIENT_USER_AGENT is required in build config')
  return resolveAppNode(node, state.settings.nodeDns, state.buildConfig.user_agent)
}

async function connectionNode(index: number): Promise<unknown> {
  const state = useAppStore.getState()
  if (state.settings.routingMode === 'direct') return { type: 'direct', name: 'DIRECT' }
  const node = state.nodes[index]
  if (!node?.connectSupported) throw new Error('unsupported_protocol')
  return resolvedNode(node)
}

async function applySessionSystemProxy(): Promise<void> {
  const state = useAppStore.getState()
  const session = state.vpn
  const socksAddr = session?.socksAddr || session?.tunSocksAddr || ''
  if (state.settings.systemProxyEnabled) {
    if (!socksAddr) throw new Error('session SOCKS address is required for system proxy')
    const parsed = parseSocksAddr(socksAddr)
    await systemProxySet(parsed.host, parsed.port)
    state.setSystemProxyActive(true)
  } else if (state.systemProxyActive) {
    await systemProxyClear()
    state.setSystemProxyActive(false)
  }
}

async function disconnectSession(): Promise<void> {
  const state = useAppStore.getState()
  const session = state.vpn
  if (!session) return
  const useTun = !session.routeMode && !session.socksAddr
  if (session.routeMode) await aerionStopRoute(session.sessionId)
  else if (useTun) await aerionStopVpn(session.sessionId)
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
  const resolved = await connectionNode(index)
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
    tunSocksAddr: handle.socks_addr,
    nodeIndex: index,
    uploadBytes: 0,
    downloadBytes: 0,
    routeMode: false,
    routingMode: state.settings.routingMode,
  })
  await reportVpnSession(handle.session_id)
  await applySessionSystemProxy()
}

async function startSocks(index: number): Promise<void> {
  const state = useAppStore.getState()
  const resolved = await connectionNode(index)
  const handle = await aerionStartSocks(resolved)
  state.setPreferredNodeIndex(index)
  state.setVpn({
    sessionId: handle.session_id,
    socksAddr: handle.socks_addr,
    nodeIndex: index,
    uploadBytes: 0,
    downloadBytes: 0,
    routeMode: false,
    routingMode: state.settings.routingMode,
  })
  await applySessionSystemProxy()
}

async function startRoute(index: number): Promise<void> {
  const state = useAppStore.getState()
  const node = state.nodes[index]
  if (!node?.connectSupported) throw new Error('unsupported_protocol')
  const resolved = await resolvedNode(node)
  const configYaml = state.settings.routeConfigYaml.trim() || state.routing.routeConfigYaml
  if (!configYaml?.trim()) throw new Error('routing_rules_missing')
  const request = {
    config_yaml: configYaml,
    geoip_dir: state.settings.geoipDir.trim() || undefined,
    global_proxy: state.settings.routingMode === 'global' ? node.name : undefined,
    selected_proxy: state.settings.routingMode === 'rule' ? node.name : undefined,
    selected_node: state.settings.routingMode === 'rule' ? resolved : undefined,
  }
  const handle = await aerionStartRoute(request)
  const parsed = parseSocksAddr(handle.socks_addr)
  state.setPreferredNodeIndex(index)
  state.setVpn({
    sessionId: handle.session_id,
    socksAddr: handle.socks_addr,
    nodeIndex: index,
    uploadBytes: 0,
    downloadBytes: 0,
    routeMode: true,
    routingMode: state.settings.routingMode,
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
  if (state.settings.routingMode !== 'direct' && !node?.connectSupported) return null

  const token = ++syncToken
  syncing = true
  try {
    const routeConfigYaml = state.settings.routeConfigYaml.trim() || state.routing.routeConfigYaml || ''
    const useRuleRouting =
      !state.settings.tunEnabled
      && state.settings.systemProxyEnabled
      && state.settings.routingMode === 'rule'
      && Boolean(routeConfigYaml.trim())
    const wantTun = state.settings.tunEnabled
    const wantSocks = !wantTun && state.settings.systemProxyEnabled && !useRuleRouting
    const session = state.vpn
    const tunSession = session && !session.socksAddr && !session.routeMode
    const routeSession = session?.routeMode === true
    const socksSession = session && Boolean(session.socksAddr) && !session.routeMode
    const modeChanged = session?.routingMode !== state.settings.routingMode

    if (useRuleRouting) {
      if (tunSession || socksSession) await disconnectSession()
      if (!routeSession || session?.nodeIndex !== nodeIndex || modeChanged) {
        if (session) await disconnectSession()
        await startRoute(nodeIndex)
      } else if (!state.systemProxyActive && session.socksAddr) {
        const parsed = parseSocksAddr(session.socksAddr)
        await systemProxySet(parsed.host, parsed.port)
        state.setSystemProxyActive(true)
      }
      return null
    }

    if (routeSession) await disconnectSession()

    if (wantTun) {
      if (socksSession || routeSession) await disconnectSession()
      if (!tunSession || session?.nodeIndex !== nodeIndex || modeChanged) {
        if (session) await disconnectSession()
        await startTun(nodeIndex)
      } else {
        await applySessionSystemProxy()
      }
      return null
    }

    if (tunSession || routeSession) await disconnectSession()
    if (wantSocks) {
      if (!socksSession || session?.nodeIndex !== nodeIndex || modeChanged) {
        if (session) await disconnectSession()
        await startSocks(nodeIndex)
      } else if (!state.systemProxyActive && session.socksAddr) {
        const parsed = parseSocksAddr(session.socksAddr)
        await systemProxySet(parsed.host, parsed.port)
        state.setSystemProxyActive(true)
      }
    } else if (session) {
      await disconnectSession()
    } else if (state.systemProxyActive) {
      await systemProxyClear()
      state.setSystemProxyActive(false)
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
