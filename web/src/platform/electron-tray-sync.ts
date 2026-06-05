import { useAppStore } from '../store'
import { reportVpnSession } from './electron'
import { isDesktopShell } from './shell'

export interface TrayNodeSnapshot {
  name: string
  host: string
  port: number
  protocolLabel: string
  connectSupported: boolean
  rawJson: string
}

export interface TrayStateSnapshot {
  nodes: TrayNodeSnapshot[]
  selectedNodeIndex: number
  vpn: {
    sessionId: number
    socksAddr: string
    tunSocksAddr?: string
    nodeIndex: number
    routeMode?: boolean
    routingMode?: 'rule' | 'global' | 'direct'
  } | null
  systemProxyOn: boolean
  useVpn: boolean
  routingMode: 'rule' | 'global' | 'direct'
  settings: {
    nodeDns: string
    overseasDns: string
    directDns: string
    vpnDnsMode: 'virtual' | 'over_tcp' | 'direct'
    virtualDnsPool: string
    vpnIpv6Enabled: boolean
    routingMode: 'rule' | 'global' | 'direct'
    tunEnabled: boolean
    systemProxyEnabled: boolean
    routeConfigYaml: string
    geoipDir: string
  }
  routingRouteConfigYaml: string
  userAgent: string
}

export interface TrayStatePushFromMain {
  settings?: Partial<TrayStateSnapshot['settings']>
  selectedNodeIndex?: number
  vpn?: {
    sessionId: number
    socksAddr: string
    tunSocksAddr?: string
    nodeIndex: number
    uploadBytes: number
    downloadBytes: number
    routeMode?: boolean
    routingMode?: 'rule' | 'global' | 'direct'
  } | null
  systemProxyOn?: boolean
}

function buildTraySnapshot(): TrayStateSnapshot {
  const state = useAppStore.getState()
  if (!state.buildConfig) throw new Error('runtime build config is required before tray sync')
  const selectedNodeIndex = state.vpn?.nodeIndex ?? state.preferredNodeIndex
  return {
    nodes: state.nodes.map((node) => ({
      name: node.name,
      host: node.host,
      port: node.port,
      protocolLabel: node.protocolLabel,
      connectSupported: node.connectSupported,
      rawJson: node.rawJson,
    })),
    selectedNodeIndex,
    vpn: state.vpn
      ? {
          sessionId: state.vpn.sessionId,
          socksAddr: state.vpn.socksAddr,
          tunSocksAddr: state.vpn.tunSocksAddr,
          nodeIndex: state.vpn.nodeIndex,
          routeMode: state.vpn.routeMode,
          routingMode: state.vpn.routingMode,
        }
      : null,
    systemProxyOn: state.systemProxyActive,
    routingMode: state.settings.routingMode,
    useVpn: state.settings.tunEnabled,
    settings: {
      nodeDns: state.settings.nodeDns,
      overseasDns: state.settings.overseasDns,
      directDns: state.settings.directDns,
      vpnDnsMode: state.settings.vpnDnsMode,
      virtualDnsPool: state.settings.virtualDnsPool,
      vpnIpv6Enabled: state.settings.vpnIpv6Enabled,
      routingMode: state.settings.routingMode,
      tunEnabled: state.settings.tunEnabled,
      systemProxyEnabled: state.settings.systemProxyEnabled,
      routeConfigYaml: state.settings.routeConfigYaml,
      geoipDir: state.settings.geoipDir,
    },
    routingRouteConfigYaml: state.routing.routeConfigYaml ?? '',
    userAgent: state.buildConfig.user_agent,
  }
}

function applyTrayPush(patch: TrayStatePushFromMain): void {
  const store = useAppStore.getState()
  if (patch.settings) {
    store.setSettings(patch.settings)
  }
  if (typeof patch.selectedNodeIndex === 'number') {
    store.setPreferredNodeIndex(patch.selectedNodeIndex)
  }
  if (patch.systemProxyOn !== undefined) {
    store.setSystemProxyActive(patch.systemProxyOn)
  }
  if ('vpn' in patch) {
    if (patch.vpn) {
      store.setVpn({
        sessionId: patch.vpn.sessionId,
        socksAddr: patch.vpn.socksAddr,
        tunSocksAddr: patch.vpn.tunSocksAddr,
        nodeIndex: patch.vpn.nodeIndex,
        uploadBytes: patch.vpn.uploadBytes,
        downloadBytes: patch.vpn.downloadBytes,
        routeMode: patch.vpn.routeMode,
        routingMode: patch.vpn.routingMode,
      })
      void reportVpnSession(patch.vpn.sessionId)
    } else {
      store.setVpn(null)
      void reportVpnSession(null)
    }
  }
}

export function installElectronTraySync(): () => void {
  if (!isDesktopShell()) return () => {}
  const api = window.electronAPI
  if (!api?.syncTrayState) return () => {}

  let pushing = false
  const push = () => {
    if (pushing) return
    pushing = true
    void api.syncTrayState(buildTraySnapshot()).finally(() => {
      pushing = false
    })
  }

  const unsubStore = useAppStore.subscribe(push)
  const unsubTray = api.onTrayStateFromMain((patch) => applyTrayPush(patch))
  push()

  return () => {
    unsubStore()
    unsubTray()
  }
}
