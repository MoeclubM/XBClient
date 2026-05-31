import { translate } from './i18n'
import type { AppNode } from './store'

export const DEFAULT_NODE_DNS = 'https://dns.alidns.com/resolve'
export const DEFAULT_NODE_TEST_TARGET = 'https://cp.cloudflare.com'
export const DEFAULT_OVERSEAS_DNS = 'https://cloudflare-dns.com/dns-query'
export const DEFAULT_DIRECT_DNS = '223.5.5.5'
export const DEFAULT_VIRTUAL_DNS_POOL = '198.18.0.0/15'

export function dnsAddressForVpn(value: string): string {
  const dns = value.trim()
  if (/^[0-9.]+$/.test(dns) || (/^[0-9A-Fa-f:.]+$/.test(dns) && dns.includes(':'))) {
    return dns
  }
  const lower = dns.toLowerCase()
  if (lower.includes('cloudflare-dns.com') || lower.includes('1.1.1.1')) {
    return '1.1.1.1'
  }
  if (lower.includes('dns.alidns.com') || lower.includes('223.5.5.5')) {
    return '223.5.5.5'
  }
  throw new Error('海外 DNS 需填写普通 DNS 地址，或已支持的 DoH 地址。')
}

export interface RawNode {
  type?: unknown
  name?: unknown
  host?: unknown
  port?: unknown
  tags?: unknown
  tag?: unknown
  label?: unknown
  group?: unknown
  sni?: unknown
  tls?: unknown
  [key: string]: unknown
}

const CONNECT_SUPPORTED = new Set([
  'anytls',
  'hysteria2',
  'trojan',
  'vless',
  'vmess',
  'mieru',
  'naive',
  'tuic',
  'ss',
  'http',
  'socks5',
  'direct',
  'block',
])

export function toAppNode(raw: RawNode): AppNode {
  if (typeof raw.type !== 'string' || !raw.type.trim()) throw new Error('XBClient 节点缺少 type。')
  if (typeof raw.host !== 'string' || !raw.host.trim()) throw new Error('XBClient 节点缺少 host。')
  if (typeof raw.name !== 'string' || !raw.name.trim()) throw new Error('XBClient 节点缺少 name。')
  const port = Number(raw.port)
  if (!Number.isInteger(port) || port <= 0 || port > 65535) throw new Error('XBClient 节点 port 无效。')
  const protocol = raw.type.trim().toLowerCase()
  const host = raw.host.trim()
  const normalized: RawNode = { ...raw, type: protocol, host }
  const tls = raw.tls as Record<string, unknown> | undefined
  if (!String(normalized.sni ?? '').trim() && tls && typeof tls.server_name === 'string' && tls.server_name.trim()) {
    normalized.sni = tls.server_name.trim()
  }
  return {
    protocol,
    protocolLabel: protocolLabel(protocol),
    name: raw.name.trim(),
    host,
    port,
    tags: nodeTags(raw),
    connectSupported: CONNECT_SUPPORTED.has(protocol),
    rawJson: JSON.stringify(normalized),
  }
}

export function displayNodeName(node: AppNode, index: number): string {
  const name = node.name.trim()
  if (!name || name === node.host || name === `${node.host}:${node.port}` || name.includes(node.host)) {
    return `Node ${index + 1}`
  }
  return name
}

export function rawNodeHost(node: AppNode): string {
  const raw = JSON.parse(node.rawJson) as RawNode
  return String(raw.host)
}

export function aerionNodeWithResolvedHost(node: AppNode, resolvedHost: string): RawNode {
  const raw = JSON.parse(node.rawJson) as RawNode
  const originalHost = String(raw.host)
  if (resolvedHost !== originalHost && !String(raw.sni ?? '').trim()) {
    raw.sni = originalHost
  }
  raw.host = resolvedHost
  return raw
}

export function targetHostPort(target: string): { host: string; port: number; tls: boolean } {
  let targetHost = target
  let targetPort = 80
  let targetTls = false
  let schemeSpecified = false
  if (target.startsWith('http://') || target.startsWith('https://')) {
    const url = new URL(target)
    targetHost = url.hostname
    targetTls = url.protocol === 'https:'
    schemeSpecified = true
    targetPort = Number(url.port || (targetTls ? 443 : 80))
  } else {
    const colon = target.lastIndexOf(':')
    if (colon > 0 && target.indexOf(':') === colon) {
      targetHost = target.slice(0, colon)
      targetPort = Number(target.slice(colon + 1))
    }
  }
  return { host: targetHost, port: targetPort, tls: schemeSpecified ? targetTls : targetPort === 443 }
}

export function readableNodeTestError(error: string, appLanguage = 'zh-CN'): string {
  const normalized = error.toLowerCase()
  if (error.includes('read AnyTLS frame header')) {
    return `${translate('node_test_failed_prefix', appLanguage)}：${translate('node_test_anytls_closed', appLanguage)}（${error}）`
  }
  if (error.includes('Hysteria2 target test')) {
    return `${translate('node_test_failed_prefix', appLanguage)}：${translate('node_test_hy2_failed', appLanguage)}（${error}）`
  }
  if (
    normalized.includes('read socks greeting response') ||
    normalized.includes('os error 10054') ||
    error.includes('\u8fdc\u7a0b\u4e3b\u673a\u5f3a\u8feb\u5173\u95ed')
  ) {
    return `${translate('node_test_failed_prefix', appLanguage)}：${translate('node_test_handshake_interrupted', appLanguage)}`
  }
  if (
    normalized.includes('timed out') ||
    normalized.includes('timeout') ||
    normalized.includes('socks connect failed: general failure') ||
    normalized.includes('read socks connect response') ||
    normalized.includes('early eof')
  ) {
    return `${translate('node_test_failed_prefix', appLanguage)}：${translate('node_test_timeout', appLanguage)}`
  }
  return `${translate('node_test_failed_prefix', appLanguage)}：${error}`
}

function protocolLabel(protocol: string): string {
  switch (protocol) {
    case 'anytls':
      return 'AnyTLS'
    case 'hysteria2':
      return 'Hysteria2'
    case 'hysteria':
      return 'Hysteria'
    case 'ss':
      return 'Shadowsocks'
    case 'vmess':
      return 'VMess'
    case 'vless':
      return 'VLESS'
    case 'trojan':
      return 'Trojan'
    case 'tuic':
      return 'TUIC'
    case 'socks':
    case 'socks5':
      return 'SOCKS5'
    case 'naive':
      return 'Naive'
    case 'http':
      return 'HTTP'
    case 'mieru':
      return 'Mieru'
    case 'direct':
      return 'Direct'
    case 'block':
      return 'Block'
    default:
      return protocol.toUpperCase()
  }
}

function nodeTags(node: RawNode): string[] {
  const tags: string[] = []
  if (Array.isArray(node.tags)) {
    for (const tag of node.tags) {
      const text = String(tag).trim()
      if (text) tags.push(text)
    }
  } else if (typeof node.tags === 'string') {
    tags.push(...node.tags.split(/[|,]/).map((tag) => tag.trim()).filter(Boolean))
  }
  for (const key of ['tag', 'label', 'group'] as const) {
    const tag = String(node[key] ?? '').trim()
    if (tag) tags.push(tag)
  }
  return [...new Set(tags)]
}

export function rawNodeRows(value: unknown): RawNode[] {
  if (value && typeof value === 'object') {
    const object = value as Record<string, unknown>
    if (Array.isArray(object.nodes)) return object.nodes as RawNode[]
  }
  throw new Error('XBClient 节点响应缺少 data.nodes 数组。')
}
