import { invoke } from '../platform/electron'

export interface TestNodeResult {
  ok: boolean
  latency_ms?: number
  first_latency_ms?: number
  target_host?: string
  target_port?: number
  target_tls?: boolean
  error?: string
}

export interface TestNodeRequest {
  node: unknown
  target_host: string
  target_port: number
  target_tls: boolean
  timeout_ms: number
}

export async function aerionTestNode(request: TestNodeRequest): Promise<TestNodeResult> {
  return invoke<TestNodeResult>('aerion_test_node', { request })
}

export interface SocksHandle {
  ok: boolean
  session_id: number
  socks_addr: string
}

export async function aerionStartSocks(node: unknown): Promise<SocksHandle> {
  return invoke<SocksHandle>('aerion_start_socks', { node })
}

export interface RouteStartRequest {
  config_yaml: string
  geoip_dir?: string
  global_proxy?: string
  selected_proxy?: string
  selected_node?: unknown
}

export async function aerionStartRoute(request: RouteStartRequest): Promise<SocksHandle & { rule_count?: number; outbound_tags?: string[] }> {
  return invoke('aerion_start_route', request)
}

export async function aerionStopRoute(sessionId: number): Promise<{ ok: boolean; session_id: number }> {
  return invoke('aerion_stop_route', { sessionId })
}

export async function aerionStop(sessionId: number): Promise<{ ok: boolean; session_id: number }> {
  return invoke('aerion_stop', { sessionId })
}

export interface VpnHandle {
  ok: boolean
  session_id: number
  socks_addr?: string
  mtu?: number
  dns?: string
  dns_addr?: string
  virtual_dns_pool?: string
}

export interface VpnStartRequest {
  node: unknown
  mtu: number
  dns: string
  dns_addr: string
  virtual_dns_pool: string
  ipv6: boolean
}

export async function aerionStartVpn(request: VpnStartRequest): Promise<VpnHandle> {
  return invoke<VpnHandle>('aerion_start_vpn', request)
}

export async function aerionStopVpn(sessionId: number): Promise<{ ok: boolean; session_id: number }> {
  return invoke('aerion_stop_vpn', { sessionId })
}
