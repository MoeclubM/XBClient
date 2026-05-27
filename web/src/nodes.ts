import { translate } from './i18n'
import type { AppNode } from './store'

export const DEFAULT_NODE_DNS = 'https://dns.alidns.com/resolve'
export const DEFAULT_NODE_TEST_TARGET = 'https://cp.cloudflare.com'
export const DEFAULT_OVERSEAS_DNS = 'https://cloudflare-dns.com/dns-query'
export const DEFAULT_DIRECT_DNS = '223.5.5.5'
export const DEFAULT_VIRTUAL_DNS_POOL = '198.18.0.0/15'

export interface RawNode {
  type?: unknown
  protocol?: unknown
  name?: unknown
  host?: unknown
  server?: unknown
  port?: unknown
  server_port?: unknown
  tags?: unknown
  tag?: unknown
  label?: unknown
  group?: unknown
  sni?: unknown
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
  const rawProtocol = String(raw.type ?? raw.protocol ?? 'unknown').toLowerCase()
  const protocol = canonicalProtocol(rawProtocol)
  const host = String(raw.host ?? raw.server ?? '')
  const port = Number(raw.port ?? raw.server_port ?? 0)
  const normalized: RawNode = { ...raw, type: protocol, host }
  if (rawProtocol === 'naive+quic') normalized.quic = true
  if (protocol !== 'ss' && normalized.insecure === undefined) {
    normalized.insecure = Boolean(raw['skip-cert-verify'])
  }
  delete normalized['skip-cert-verify']
  return {
    protocol,
    protocolLabel: protocolLabel(protocol),
    name: String(raw.name ?? raw.tag ?? '').trim() || `${host}:${port}`,
    host,
    port,
    tags: nodeTags(raw),
    connectSupported: CONNECT_SUPPORTED.has(protocol),
    rawJson: JSON.stringify(normalized),
  }
}

export function mergeNodeLists(...lists: AppNode[][]): AppNode[] {
  const seen = new Set<string>()
  const result: AppNode[] = []
  for (const node of lists.flat()) {
    const key = `${node.protocol}|${node.host}|${node.port}|${node.name}`
    if (!seen.has(key)) {
      seen.add(key)
      result.push(node)
    }
  }
  return result
}

export function mergeXboardNodeTags(nodes: AppNode[], rows: RawNode[]): AppNode[] {
  const tagsById = new Map<number, string[]>()
  const tagsByTypedName = new Map<string, string[]>()
  const tagsByName = new Map<string, string[]>()
  for (const row of rows) {
    const tags = nodeTags(row)
    if (tags.length === 0) continue
    const id = Number(row.id ?? 0)
    if (id > 0) tagsById.set(id, tags)
    const name = String(row.name ?? '').trim()
    const type = canonicalProtocol(String(row.type ?? row.protocol ?? '').toLowerCase())
    if (name) {
      tagsByName.set(name, tags)
      if (type) tagsByTypedName.set(`${type}|${name}`, tags)
    }
  }
  return nodes.map((node) => {
    const raw = JSON.parse(node.rawJson) as RawNode
    const id = Number(raw.id ?? 0)
    const tags =
      node.tags.length > 0
        ? node.tags
        : id > 0
          ? tagsById.get(id) ?? []
          : tagsByTypedName.get(`${node.protocol}|${node.name.trim()}`) ??
            tagsByName.get(node.name.trim()) ??
            []
    return tags.length === 0 || tags === node.tags ? node : { ...node, tags }
  })
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
  return String(raw.host ?? raw.server ?? node.host)
}

export function aerionNodeWithResolvedHost(node: AppNode, resolvedHost: string): RawNode {
  const raw = JSON.parse(node.rawJson) as RawNode
  const originalHost = String(raw.host ?? raw.server ?? node.host)
  if (resolvedHost !== originalHost && !String(raw.sni ?? '').trim()) {
    raw.sni = originalHost
  }
  raw.host = resolvedHost
  if (raw.server !== undefined) {
    raw.server = resolvedHost
  }
  if (raw.address !== undefined) raw.address = resolvedHost
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

function canonicalProtocol(rawProtocol: string): string {
  switch (rawProtocol) {
    case 'hy2':
      return 'hysteria2'
    case 'mierus':
      return 'mieru'
    case 'naive+https':
    case 'naive+quic':
      return 'naive'
    case 'shadowsocks':
      return 'ss'
    case 'socks':
    case 'socks5h':
      return 'socks5'
    case 'freedom':
      return 'direct'
    case 'reject':
    case 'blackhole':
      return 'block'
    default:
      return rawProtocol || 'unknown'
  }
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
