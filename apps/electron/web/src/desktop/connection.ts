import { aerionStartRoute, aerionStartSocks, aerionStartVpn, aerionStop, aerionStopRoute, aerionStopVpn } from '../api/aerion'
import {
  localProxyClear,
  localProxySync,
  parseSocksAddr,
  resolveAppNode,
} from '../api/system'
import {
  dnsAddressForVpn,
} from '../nodes'
import { publicErrorText } from '../format'
import { translate } from '../i18n'
import { reportVpnSession } from '../platform/electron'
import { isDesktopShell } from '../platform/shell'
import { useAppStore, type AppNode } from '../store'
import { ref } from 'vue'

const pendingSyncs = ref(0)
let syncTail: Promise<string | null> = Promise.resolve(null)

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
  if (!socksAddr) {
    await localProxyClear()
    state.setSystemProxyActive(false)
    state.setHttpProxyAddr('')
    return
  }
  const parsed = parseSocksAddr(socksAddr)
  const synced = await localProxySync({
    host: parsed.host,
    port: parsed.port,
    enableSystemProxy: state.settings.systemProxyEnabled,
    listenPort: state.settings.proxyPort,
    allowLan: state.settings.allowLan,
    bypass: state.settings.systemProxyBypass,
  })
  state.setSystemProxyActive(synced.systemProxyOn)
  state.setHttpProxyAddr(`127.0.0.1:${synced.httpPort}`)
}

async function disconnectSession(): Promise<void> {
  const state = useAppStore.getState()
  await localProxyClear()
  state.setSystemProxyActive(false)
  state.setHttpProxyAddr('')
  const session = state.vpn
  if (!session) return
  const useTun = !session.routeMode && !session.socksAddr
  if (session.routeMode) await aerionStopRoute(session.sessionId)
  else if (useTun) await aerionStopVpn(session.sessionId)
  else await aerionStop(session.sessionId)
  state.setVpn(null)
  await reportVpnSession(null)
}

async function startTun(index: number): Promise<void> {
  const state = useAppStore.getState()
  const resolved = await connectionNode(index)
  const node = state.nodes[index]
  const configYaml = (state.settings.routeConfigYaml.trim() || state.routing.routeConfigYaml || '').trim()
  if (state.settings.routingMode === 'rule' && !configYaml) {
    throw new Error(translate('routing_rules_missing', state.settings.appLanguage))
  }
  const dnsMode = state.settings.vpnDnsMode
  const dns_addr = dnsAddressForVpn(
    dnsMode === 'direct' ? state.settings.directDns : state.settings.overseasDns,
  )
  const handle = await aerionStartVpn({
    node: resolved,
    ...(state.settings.routingMode === 'rule' && node
      ? {
          route: {
            config_yaml: configYaml,
            geoip_dir: state.settings.geoipDir.trim() || undefined,
            selected_proxy: node.name,
            selected_node: resolved,
          },
        }
      : {}),
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
  if (!configYaml?.trim()) throw new Error(translate('routing_rules_missing', state.settings.appLanguage))
  const request = {
    config_yaml: configYaml,
    geoip_dir: state.settings.geoipDir.trim() || undefined,
    global_proxy: state.settings.routingMode === 'global' ? node.name : undefined,
    selected_proxy: state.settings.routingMode === 'rule' ? node.name : undefined,
    selected_node: state.settings.routingMode === 'rule' ? resolved : undefined,
  }
  const handle = await aerionStartRoute(request)
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
  await applySessionSystemProxy()
}

function queueConnection(action: () => Promise<string | null>): Promise<string | null> {
  pendingSyncs.value++
  const next = syncTail.then(action).finally(() => { pendingSyncs.value-- })
  syncTail = next.catch(() => null)
  return next
}

export function applyDesktopConnection(): Promise<string | null> {
  return queueConnection(syncDesktopConnection)
}

export async function disconnectDesktopConnection(): Promise<void> {
  await queueConnection(async () => {
    await disconnectSession()
    return null
  })
}

async function syncDesktopConnection(): Promise<string | null> {
  if (!isDesktopConnectionShell()) return null
  const state = useAppStore.getState()
  if (state.subscription.blockReason || !state.authData) {
    await disconnectSession()
    return null
  }
  const nodeIndex = state.preferredNodeIndex
  const node = state.nodes[nodeIndex]
  if (state.settings.routingMode !== 'direct' && !node?.connectSupported) return null

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
      } else if (session.socksAddr) {
        await applySessionSystemProxy()
      }
      return null
    }

    if (routeSession) await disconnectSession()

    if (wantTun) {
      if (state.capabilities?.tun_elevated === false) {
        if (session) await disconnectSession()
        return null
      }
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
      } else if (session.socksAddr) {
        await applySessionSystemProxy()
      }
    } else if (session) {
      await disconnectSession()
    } else if (state.systemProxyActive || state.httpProxyAddr) {
      await localProxyClear()
      state.setSystemProxyActive(false)
      state.setHttpProxyAddr('')
    }
    return null
  } catch (err) {
    try {
      await disconnectSession()
    } catch (cleanupError) {
      return `${publicErrorText(err)}\n${publicErrorText(cleanupError)}`
    }
    return publicErrorText(err)
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

export function reapplyLocalProxySettings(): Promise<string | null> {
  return queueConnection(async () => {
    if (!isDesktopConnectionShell() || !useAppStore.getState().vpn) return null
    try {
      await applySessionSystemProxy()
      return null
    } catch (err) {
      return publicErrorText(err)
    }
  })
}

export function buildTerminalProxyCommand(shell: 'powershell' | 'cmd' | 'bash'): string | null {
  const state = useAppStore.getState()
  const httpAddr = state.httpProxyAddr
  const socksAddr = state.vpn?.socksAddr || state.vpn?.tunSocksAddr || ''
  if (!httpAddr && !socksAddr) return null
  const httpUrl = httpAddr ? `http://${httpAddr}` : `socks5://${socksAddr}`
  const socksUrl = socksAddr ? `socks5://${socksAddr}` : httpUrl
  if (shell === 'powershell') {
    return `$env:HTTP_PROXY="${httpUrl}"; $env:HTTPS_PROXY="${httpUrl}"; $env:ALL_PROXY="${socksUrl}"`
  }
  if (shell === 'cmd') {
    return `set HTTP_PROXY=${httpUrl} && set HTTPS_PROXY=${httpUrl} && set ALL_PROXY=${socksUrl}`
  }
  return `export http_proxy="${httpUrl}" https_proxy="${httpUrl}" all_proxy="${socksUrl}"`
}

export function desktopConnectionBusy(): boolean {
  return pendingSyncs.value > 0
}
