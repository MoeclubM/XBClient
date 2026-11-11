import assert from 'node:assert/strict'
import { after, before, test } from 'node:test'
import { spawn, execFile } from 'node:child_process'
import { once } from 'node:events'
import http from 'node:http'
import net from 'node:net'
import path from 'node:path'
import readline from 'node:readline'
import { promisify } from 'node:util'
import { fileURLToPath } from 'node:url'
import { startHttpProxy } from '../runtime/http-proxy.js'
import { clearWindowsSystemProxy, setWindowsSystemProxy } from '../runtime/system-proxy.js'

const execute = promisify(execFile)
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const routeAssetsDir = path.resolve(root, '../../rust/aerion-core/assets/route')
const backend = spawn(
  path.join(root, 'backend/target/release', `xbclient-electron-backend${process.platform === 'win32' ? '.exe' : ''}`),
  [],
  {
    stdio: ['pipe', 'pipe', 'inherit'],
    env: { ...process.env, XBCLIENT_ROUTE_ASSETS_DIR: routeAssetsDir },
  },
)
const replies = new Map<number, { resolve: (value: any) => void; reject: (error: Error) => void }>()
let nextId = 0
const lines = readline.createInterface({ input: backend.stdout })
lines.on('line', (line) => {
  const response = JSON.parse(line)
  const pending = replies.get(response.id)
  if (!pending) return
  replies.delete(response.id)
  if (response.ok) pending.resolve(response.result)
  else pending.reject(new Error(response.error))
})
backend.on('exit', (code) => {
  for (const pending of replies.values()) pending.reject(new Error(`backend exited: ${code}`))
  replies.clear()
})

function invoke(method: string, params: unknown) {
  return new Promise<any>((resolve, reject) => {
    const id = ++nextId
    replies.set(id, { resolve, reject })
    backend.stdin.write(JSON.stringify({ id, method, params }) + '\n')
  })
}

let handle: { session_id: number; socks_addr: string }
let proxy: Awaited<ReturnType<typeof startHttpProxy>>
let socksPort: number
before(async () => {
  handle = await invoke('aerion_start_socks', { node: { type: 'direct' } })
  socksPort = Number(handle.socks_addr.split(':').at(-1))
  proxy = await startHttpProxy('127.0.0.1', socksPort)
})
after(async () => {
  if (process.platform === 'win32') await clearWindowsSystemProxy()
  await proxy?.close()
  if (handle) await invoke('aerion_stop', { sessionId: handle.session_id })
  backend.stdin.end()
  await once(backend, 'exit')
})

test('HTTP requests and bodies pass through the actual Aerion SOCKS listener', { timeout: 15000 }, async () => {
  const origin = http.createServer((request, response) => {
    assert.equal(request.headers['proxy-authorization'], undefined)
    response.writeHead(200, { 'content-type': 'text/plain' })
    request.pipe(response)
  })
  origin.listen(0)
  await once(origin, 'listening')
  try {
    const port = (origin.address() as net.AddressInfo).port
    const result = await new Promise<string>((resolve, reject) => {
      const request = http.request({ host: '127.0.0.1', port: proxy.port, path: `http://localhost:${port}/body`, method: 'POST', headers: { 'proxy-authorization': 'should-stay-local' } }, async (response) => {
        assert.equal(response.statusCode, 200)
        const chunks: Buffer[] = []
        for await (const chunk of response) chunks.push(chunk)
        resolve(Buffer.concat(chunks).toString())
      })
      request.on('error', reject)
      request.end('actual HTTP through SOCKS')
    })
    assert.equal(result, 'actual HTTP through SOCKS')
  } finally {
    origin.closeAllConnections()
    await new Promise<void>((resolve) => origin.close(() => resolve()))
  }
})

test('HTTPS CONNECT transfers data and closing the bridge releases the tunnel', { timeout: 15000 }, async () => {
  const origin = net.createServer((socket) => socket.pipe(socket))
  origin.listen(0, '127.0.0.1')
  await once(origin, 'listening')
  const bridge = await startHttpProxy('127.0.0.1', socksPort)
  try {
    const port = (origin.address() as net.AddressInfo).port
    const request = http.request({ host: '127.0.0.1', port: bridge.port, method: 'CONNECT', path: `127.0.0.1:${port}` })
    request.end()
    const [response, socket] = await once(request, 'connect')
    assert.equal(response.statusCode, 200)
    socket.write('CONNECT payload')
    assert.equal((await once(socket, 'data'))[0].toString(), 'CONNECT payload')
    const closed = once(socket, 'close')
    await bridge.close()
    await closed
  } finally {
    await new Promise<void>((resolve) => origin.close(() => resolve()))
  }
})

async function proxySnapshot() {
  const command = "$p='HKCU:\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings'; $o=[ordered]@{}; foreach($n in @('ProxyEnable','ProxyServer','ProxyOverride','AutoConfigURL','AutoDetect')){$o[$n]=(Get-ItemProperty -Path $p -Name $n -ErrorAction SilentlyContinue).$n}; foreach($n in @('DefaultConnectionSettings','SavedLegacySettings')){$o[$n]=(Get-ItemProperty -Path \"$p\\Connections\" -Name $n -ErrorAction SilentlyContinue).$n}; $o | ConvertTo-Json -Compress"
  const { stdout } = await execute('powershell.exe', ['-NoProfile', '-NonInteractive', '-EncodedCommand', Buffer.from(command, 'utf16le').toString('base64')])
  return JSON.parse(stdout)
}

test('WebSocket HTTP upgrade passes through the actual SOCKS listener', { timeout: 15000 }, async () => {
  const origin = http.createServer()
  origin.on('upgrade', (_request, socket, head) => {
    socket.write('HTTP/1.1 101 Switching Protocols\r\nConnection: Upgrade\r\nUpgrade: websocket\r\n\r\n')
    if (head.length) socket.write(head)
    socket.pipe(socket)
  })
  origin.listen(0, '127.0.0.1')
  await once(origin, 'listening')
  const port = (origin.address() as net.AddressInfo).port
  try {
    const request = http.request({ host: '127.0.0.1', port: proxy.port, path: `http://127.0.0.1:${port}/socket`, headers: { connection: 'Upgrade', upgrade: 'websocket' } })
    request.end()
    const [response, socket] = await once(request, 'upgrade')
    assert.equal(response.statusCode, 101)
    socket.write('upgraded connection')
    assert.equal((await once(socket, 'data'))[0].toString(), 'upgraded connection')
    socket.destroy()
  } finally {
    await new Promise<void>((resolve) => origin.close(() => resolve()))
  }
})

test('Windows proxy replacement restores manual, automatic and binary connection settings', { skip: process.platform !== 'win32', timeout: 60000 }, async () => {
  const original = await proxySnapshot()
  try {
    await setWindowsSystemProxy('127.0.0.1', socksPort, { bypass: '<local>;localhost;127.*;192.168.*;*.corp.internal' })
    const enabled = await proxySnapshot()
    assert.equal(enabled.ProxyEnable, 1)
    assert.match(enabled.ProxyServer, /^http=127\.0\.0\.1:\d+;https=127\.0\.0\.1:\d+$/)
    assert.match(enabled.ProxyOverride, /corp\.internal/)
    await setWindowsSystemProxy('127.0.0.1', socksPort)
    await clearWindowsSystemProxy()
    assert.deepEqual(await proxySnapshot(), original)
  } finally {
    await clearWindowsSystemProxy()
  }
})

test('HTTP proxy bridge rejects occupied fixed port explicitly', { timeout: 15000 }, async () => {
  const blocker = net.createServer()
  blocker.listen(0, '127.0.0.1')
  await once(blocker, 'listening')
  const occupiedPort = (blocker.address() as net.AddressInfo).port
  try {
    await assert.rejects(
      () => startHttpProxy('127.0.0.1', socksPort, { listenPort: occupiedPort }),
      new RegExp(String(occupiedPort)),
    )
  } finally {
    await new Promise<void>((resolve) => blocker.close(() => resolve()))
  }
})

test('aerion_test_route evaluates real Mihomo rules and returns matched rule', { timeout: 15000 }, async () => {
  const configYaml = [
    'proxies: []',
    'rules:',
    '  - DOMAIN-SUFFIX,openai.com,Proxy',
    '  - IP-CIDR,192.168.0.0/16,DIRECT,no-resolve',
    '  - MATCH,Proxy',
  ].join('\n')
  const hit = await invoke('aerion_test_route', {
    config_yaml: configYaml,
    selected_proxy: 'Tokyo-01',
    target: 'https://api.openai.com/v1',
    network: 'tcp',
  })
  assert.equal(hit.decision, 'PROXY')
  assert.equal(hit.outbound, 'Tokyo-01')
  assert.equal(hit.matched_rule, 'DOMAIN-SUFFIX,openai.com,Tokyo-01')

  const lan = await invoke('aerion_test_route', {
    config_yaml: configYaml,
    selected_proxy: 'Tokyo-01',
    target: '192.168.1.10:8080',
    network: 'tcp',
  })
  assert.equal(lan.decision, 'DIRECT')
  assert.equal(lan.matched_rule, 'IP-CIDR,192.168.0.0/16,DIRECT,no-resolve')
})

test('HTTP and HTTPS CONNECT surface real connection_refused Proxy-Status on closed port', { timeout: 15000 }, async () => {
  const ephemeral = net.createServer()
  ephemeral.listen(0, '127.0.0.1')
  await once(ephemeral, 'listening')
  const closedPort = (ephemeral.address() as net.AddressInfo).port
  await new Promise<void>((resolve) => ephemeral.close(() => resolve()))

  const httpResult = await new Promise<{ statusCode: number; proxyStatus: string }>((resolve, reject) => {
    const req = http.request(
      { host: '127.0.0.1', port: proxy.port, path: `http://127.0.0.1:${closedPort}/probe`, method: 'GET' },
      (res) => {
        res.resume()
        resolve({
          statusCode: res.statusCode ?? 0,
          proxyStatus: String(res.headers['proxy-status'] ?? ''),
        })
      },
    )
    req.on('error', reject)
    req.end()
  })
  assert.equal(httpResult.statusCode, 502)
  assert.match(httpResult.proxyStatus, /error=connection_refused/)

  const connectResult = await new Promise<{ statusCode: number; proxyStatus: string }>((resolve, reject) => {
    const req = http.request({
      host: '127.0.0.1',
      port: proxy.port,
      method: 'CONNECT',
      path: `127.0.0.1:${closedPort}`,
    })
    req.on('connect', (res, socket) => {
      socket.destroy()
      resolve({
        statusCode: res.statusCode ?? 0,
        proxyStatus: String(res.headers['proxy-status'] ?? ''),
      })
    })
    req.on('error', reject)
    req.end()
  })
  assert.equal(connectResult.statusCode, 502)
  assert.match(connectResult.proxyStatus, /error=connection_refused/)
})

test('aerion_test_route sanitizes binary mrs rule-providers and rewrites cn rules', { timeout: 15000 }, async () => {
  const configYaml = [
    'proxies: []',
    'rule-providers:',
    '  cn-domain:',
    '    type: http',
    '    behavior: domain',
    '    format: mrs',
    '    url: https://example.com/cn-domain.mrs',
    '    path: ./ruleset/cn-domain.mrs',
    '  cn-ip:',
    '    type: http',
    '    behavior: ipcidr',
    '    format: mrs',
    '    url: https://example.com/cn-ip.mrs',
    '    path: ./ruleset/cn-ip.mrs',
    'rules:',
    '  - RULE-SET,cn-domain,DIRECT',
    '  - RULE-SET,cn-ip,DIRECT,no-resolve',
    '  - MATCH,Proxy',
  ].join('\n')
  const cnHit = await invoke('aerion_test_route', {
    config_yaml: configYaml,
    selected_proxy: 'Tokyo-01',
    target: 'https://www.gov.cn',
    network: 'tcp',
  })
  assert.equal(cnHit.decision, 'DIRECT')
  assert.equal(cnHit.matched_rule, 'DOMAIN-SUFFIX,cn,DIRECT')

  const proxyHit = await invoke('aerion_test_route', {
    config_yaml: configYaml,
    selected_proxy: 'Tokyo-01',
    target: 'https://api.openai.com',
    network: 'tcp',
  })
  assert.equal(proxyHit.decision, 'PROXY')
  assert.equal(proxyHit.outbound, 'Tokyo-01')
})
