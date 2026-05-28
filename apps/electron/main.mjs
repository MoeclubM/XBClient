import { app, BrowserWindow, dialog, ipcMain, shell, Tray, Menu, nativeImage } from 'electron'
import path from 'node:path'
import { spawn, execSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import readline from 'node:readline'
import fs from 'node:fs'
import AutoLaunch from 'auto-launch'
import { setupAutoUpdater } from './updater.mjs'

if (process.platform === 'linux') {
  app.commandLine.appendSwitch('enable-features', 'UseOzonePlatform')
}

const __filename = fileURLToPath(import.meta.url)
const __dirname = path.dirname(__filename)
const repoRoot = path.resolve(__dirname, '../..')

/** Electron 桌面端当前仅支持 Windows / Linux；macOS 预留，移动端不使用 Electron。 */
const ELECTRON_SUPPORTED_PLATFORMS = new Set(['win32', 'linux'])

const isPackaged = app.isPackaged
const isDev = !isPackaged && process.argv[2] !== 'build'

let mainWindow = null
let backendProc = null
let backendNextId = 1
const backendPending = new Map()
let quitRequested = false
let viteProc = null
let pendingOAuthUrl = null
const oauthBrowserWindows = new Set()
let activeVpnSessionId = null
let backendIsReady = false
let backendStderr = ''
let backendBootPromise = null
let lastBackendStartAt = 0
let trayInstance = null
let trayBusy = false
let autoLauncherInstance = null
const trayState = {
  nodes: [],
  selectedNodeIndex: 0,
  vpn: null,
  systemProxyOn: false,
  useVpn: true,
  routingMode: 'rule',
  settings: {
    nodeDns: '',
    overseasDns: '',
    directDns: '',
    vpnDnsMode: 'virtual',
    virtualDnsPool: '',
    vpnIpv6Enabled: false,
    routingMode: 'rule',
    tunEnabled: true,
    systemProxyEnabled: false,
  },
  userAgent: '',
}

function parsePropertiesFile(file) {
  const lines = fs.readFileSync(file, 'utf8').split(/\r?\n/)
  const entries = {}
  for (const line of lines) {
    const text = line.trim()
    if (!text || text.startsWith('#') || text.startsWith('!')) continue
    const idx = text.search(/[:=]/)
    if (idx <= 0) continue
    entries[text.slice(0, idx).trim()] = text.slice(idx + 1).trim()
  }
  return entries
}

function configFileCandidates() {
  if (isPackaged) {
    const files = [
      path.join(process.resourcesPath, 'config', 'local.properties'),
      path.join(path.dirname(process.execPath), 'local.properties'),
    ]
    if (app.isReady()) {
      files.push(path.join(app.getPath('userData'), 'local.properties'))
    }
    return files
  }
  return [path.resolve(repoRoot, 'local.properties')]
}

function readLocalProperties() {
  try {
    for (const file of configFileCandidates()) {
      if (fs.existsSync(file)) return parsePropertiesFile(file)
    }
    return {}
  } catch {
    return {}
  }
}

function oauthScheme() {
  const localProps = readLocalProperties()
  return (
    process.env.XBCLIENT_OAUTH_CALLBACK_SCHEME ||
    localProps['xbclient.oauthCallbackScheme'] ||
    ''
  ).trim()
}

function registerOAuthProtocol() {
  if (!ELECTRON_SUPPORTED_PLATFORMS.has(process.platform)) return
  const scheme = oauthScheme()
  if (!scheme) return

  if (isPackaged) {
    app.setAsDefaultProtocolClient(scheme)
  } else {
    app.setAsDefaultProtocolClient(scheme, process.execPath, [app.getAppPath(), isDev ? 'dev' : 'build'])
  }
}

function handleOAuthArgv(argv) {
  const scheme = oauthScheme()
  if (!scheme) return
  const prefix = `${scheme}:`
  for (const arg of argv) {
    if (typeof arg === 'string' && arg.startsWith(prefix)) {
      deliverOAuthUrl(arg)
    }
  }
}

function deliverOAuthUrl(url) {
  pendingOAuthUrl = url
  for (const win of oauthBrowserWindows) {
    if (!win.isDestroyed()) win.close()
  }
  oauthBrowserWindows.clear()
  if (mainWindow && !mainWindow.isDestroyed()) {
    mainWindow.webContents.send('oauth-callback', url)
    showMainWindow()
  }
}

const instanceLock = app.requestSingleInstanceLock()
if (!instanceLock) {
  app.quit()
} else {
  app.on('second-instance', (_event, argv) => {
    handleOAuthArgv(argv)
    showMainWindow()
  })
}

registerOAuthProtocol()

function backendManifestPath() {
  return path.resolve(repoRoot, 'rust/electron-backend/Cargo.toml')
}

function backendBinaryName() {
  return process.platform === 'win32' ? 'xbclient-electron-backend.exe' : 'xbclient-electron-backend'
}

function backendReleaseBinaryPath() {
  if (isPackaged) {
    return path.join(process.resourcesPath, 'bin', backendBinaryName())
  }
  return path.resolve(repoRoot, 'rust/electron-backend/target/release', backendBinaryName())
}

function desktopRuntimeConfig() {
  const env = buildBackendEnv()
  return {
    app_name: env.XBCLIENT_APP_NAME,
    default_api_url: env.XBCLIENT_DEFAULT_API_URL,
    user_agent: env.XBCLIENT_USER_AGENT,
    oauth_callback_scheme: env.XBCLIENT_OAUTH_CALLBACK_SCHEME,
  }
}

function isProcessElevated() {
  if (process.platform === 'win32') {
    try {
      execSync('net session', { stdio: 'ignore' })
      return true
    } catch {
      return false
    }
  }
  if (process.platform === 'linux') {
    try {
      fs.accessSync('/dev/net/tun', fs.constants.R_OK | fs.constants.W_OK)
      return true
    } catch {
      return false
    }
  }
  return true
}

function desktopRuntimeCapabilities() {
  const desktop = ELECTRON_SUPPORTED_PLATFORMS.has(process.platform)
  const platform =
    process.platform === 'win32' ? 'windows'
    : process.platform === 'linux' ? 'linux'
    : process.platform === 'darwin' ? 'macos'
    : process.platform
  return {
    platform,
    system_proxy: desktop,
    oauth_callback: desktop,
    autostart: desktop,
    tray: desktop,
    local_socks: true,
    vpn: desktop,
    tun_elevated: desktop ? isProcessElevated() : false,
    payment: true,
  }
}

function launchedSilentArgv() {
  return process.argv.includes('--silent')
}

function buildBackendEnv() {
  const localProps = readLocalProperties()
  const env = { ...process.env }
  const appName = env.XBCLIENT_APP_NAME || localProps['xbclient.appName']
  const defaultApiUrl = env.XBCLIENT_DEFAULT_API_URL || localProps['xbclient.defaultApiUrl']
  const userAgent = env.XBCLIENT_USER_AGENT || localProps['xbclient.userAgent']
  const oauthCallbackScheme = env.XBCLIENT_OAUTH_CALLBACK_SCHEME || localProps['xbclient.oauthCallbackScheme']
  if (appName) env.XBCLIENT_APP_NAME = appName
  if (defaultApiUrl) env.XBCLIENT_DEFAULT_API_URL = defaultApiUrl
  if (userAgent) env.XBCLIENT_USER_AGENT = userAgent
  if (oauthCallbackScheme) env.XBCLIENT_OAUTH_CALLBACK_SCHEME = oauthCallbackScheme
  return env
}

function validateBackendEnv() {
  const env = buildBackendEnv()
  const missing = []
  for (const key of ['XBCLIENT_APP_NAME', 'XBCLIENT_DEFAULT_API_URL', 'XBCLIENT_USER_AGENT', 'XBCLIENT_OAUTH_CALLBACK_SCHEME']) {
    if (!env[key]?.trim()) missing.push(key)
  }
  if (!missing.length) return env
  const message = `缺少构建配置：${missing.join(', ')}\n请在 local.properties 或环境变量中设置（与 Android 相同字段）。`
  dialog.showErrorBox('XBClient 配置不完整', message)
  throw new Error(message)
}

function backendStart() {
  if (backendProc) {
    backendProc.removeAllListeners()
    try {
      backendProc.kill()
    } catch {}
    backendProc = null
  }
  const env = validateBackendEnv()
  const releaseBinary = backendReleaseBinaryPath()
  backendStderr = ''
  lastBackendStartAt = Date.now()

  if (isDev && !fs.existsSync(releaseBinary)) {
    backendProc = spawn('cargo', ['run', '--quiet', '--manifest-path', backendManifestPath()], {
      cwd: repoRoot,
      env,
      stdio: ['pipe', 'pipe', 'pipe'],
    })
  } else {
    if (!fs.existsSync(releaseBinary)) {
      throw new Error(
        `未找到 electron-backend：${releaseBinary}\n请先运行：pnpm --filter xbclient-electron build:backend`,
      )
    }
    backendProc = spawn(releaseBinary, [], {
      cwd: path.dirname(releaseBinary),
      env,
      stdio: ['pipe', 'pipe', 'pipe'],
      windowsHide: true,
    })
  }

  backendProc.on('error', (err) => {
    backendIsReady = false
    const error = new Error(`electron-backend 无法启动：${err.message}`)
    for (const [, pending] of backendPending.entries()) pending.reject(error)
    backendPending.clear()
    notifyBackendError(error)
  })

  backendProc.on('exit', (code, signal) => {
    backendIsReady = false
    backendProc = null
    const detail = backendStderr.trim() ? `\n${backendStderr.trim().slice(-2000)}` : ''
    const error = new Error(
      signal
        ? `electron-backend 被信号终止：${signal}${detail}`
        : `electron-backend 异常退出（code ${code ?? 'null'}）${detail}`,
    )
    for (const [, pending] of backendPending.entries()) pending.reject(error)
    backendPending.clear()
    if (!quitRequested) {
      console.error('[backend:exit]', error.message)
    }
  })

  backendProc.stdout.setEncoding('utf8')
  const rl = readline.createInterface({ input: backendProc.stdout })
  rl.on('line', (line) => {
    const text = line.trim()
    if (!text) return
    if (!text.startsWith('{')) {
      console.log('[backend:stdout]', text.slice(0, 500))
      return
    }

    try {
      const msg = JSON.parse(text)
      if (msg?.type === 'event') {
        if (mainWindow) mainWindow.webContents.send('aerion-event', msg.payload)
        return
      }
      if (msg?.type === 'log') {
        console.log('[backend]', msg.level, msg.message)
        return
      }
      if (typeof msg?.id === 'number') {
        const pending = backendPending.get(msg.id)
        if (!pending) return
        backendPending.delete(msg.id)
        if (msg.ok) pending.resolve(msg.result)
        else pending.reject(new Error(msg.error || 'backend error'))
        return
      }
      console.warn('Unknown backend message', msg)
    } catch {
      console.debug('Non-json backend output:', text.slice(0, 200))
    }
  })

  backendProc.stderr.on('data', (data) => {
    const chunk = String(data)
    backendStderr = (backendStderr + chunk).slice(-8000)
    console.error('[backend:stderr]', chunk.trim())
  })
}

async function ensureBackendRunning() {
  if (backendIsReady && backendProc?.stdin) return
  if (!backendBootPromise) {
    backendBootPromise = (async () => {
      try {
        if (!backendProc) backendStart()
        await waitBackendReady()
        notifyBackendReady()
      } finally {
        backendBootPromise = null
      }
    })()
  }
  await backendBootPromise
}

async function waitBackendReady() {
  const deadline = Date.now() + 120_000
  while (Date.now() < deadline) {
    if (!backendProc?.stdin && Date.now() - lastBackendStartAt > 1000) backendStart()
    try {
      await backendInvoke('runtime_capabilities', {})
      return
    } catch {
      await new Promise((resolve) => setTimeout(resolve, 200))
    }
  }
  const detail = backendStderr.trim() ? `\n${backendStderr.trim().slice(-2000)}` : ''
  throw new Error(`electron-backend 启动超时${detail}`)
}

function backendInvoke(method, params, timeoutMs = 120_000) {
  if (!backendProc?.stdin) return Promise.reject(new Error('backend not started'))

  const id = backendNextId++
  const payload = JSON.stringify({ id, method, params }) + '\n'

  return new Promise((resolve, reject) => {
    const timer =
      timeoutMs > 0
        ? setTimeout(() => {
            if (!backendPending.has(id)) return
            backendPending.delete(id)
            reject(new Error(`backend request timeout: ${method}`))
          }, timeoutMs)
        : null
    backendPending.set(id, {
      resolve: (value) => {
        if (timer) clearTimeout(timer)
        resolve(value)
      },
      reject: (error) => {
        if (timer) clearTimeout(timer)
        reject(error)
      },
    })
    backendProc.stdin.write(payload, (err) => {
      if (err) {
        backendPending.delete(id)
        if (timer) clearTimeout(timer)
        reject(err)
      }
    })
  })
}

function notifyBackendReady() {
  backendIsReady = true
  if (mainWindow && !mainWindow.isDestroyed()) {
    mainWindow.webContents.send('backend-ready')
  }
}

function startViteDevServer() {
  if (!isDev || viteProc) return Promise.resolve()
  return new Promise((resolve, reject) => {
    viteProc = spawn('pnpm', ['--filter', 'xbclient-web', 'dev'], {
      cwd: repoRoot,
      shell: true,
      stdio: 'inherit',
      env: process.env,
    })
    viteProc.on('error', reject)

    const deadline = Date.now() + 60_000
    const tick = () => {
      fetch('http://127.0.0.1:5173/')
        .then(() => resolve())
        .catch(() => {
          if (Date.now() > deadline) {
            reject(new Error('Vite dev server did not start on http://127.0.0.1:5173'))
            return
          }
          setTimeout(tick, 500)
        })
    }
    setTimeout(tick, 1000)
  })
}

function webIndexHtml() {
  if (isPackaged) {
    return path.join(process.resourcesPath, 'web', 'dist', 'index.html')
  }
  return path.resolve(repoRoot, 'web/dist/index.html')
}

function appIconPath() {
  const packaged = path.join(process.resourcesPath, 'icon.png')
  if (isPackaged && fs.existsSync(packaged)) return packaged
  const built = path.join(__dirname, 'build', 'icon.png')
  if (fs.existsSync(built)) return built
  const logo = path.join(repoRoot, 'web/public/logo.png')
  if (fs.existsSync(logo)) return logo
  return ''
}

function loadAppIcon() {
  const file = appIconPath()
  if (!file) return nativeImage.createEmpty()
  const image = nativeImage.createFromPath(file)
  return image.isEmpty() ? nativeImage.createEmpty() : image
}

function createMainWindow() {
  const icon = loadAppIcon()
  const startHidden = launchedSilentArgv()
  mainWindow = new BrowserWindow({
    width: 1024,
    height: 720,
    minWidth: 800,
    minHeight: 600,
    show: !startHidden,
    frame: false,
    ...(process.platform === 'darwin' ? { titleBarStyle: 'hiddenInset' } : {}),
    ...(icon.isEmpty() ? {} : { icon }),
    webPreferences: {
      preload: path.resolve(__dirname, 'preload.cjs'),
      contextIsolation: true,
      nodeIntegration: false,
    },
  })

  mainWindow.on('maximize', () => {
    if (!mainWindow.isDestroyed()) mainWindow.webContents.send('window-maximized-changed', true)
  })
  mainWindow.on('unmaximize', () => {
    if (!mainWindow.isDestroyed()) mainWindow.webContents.send('window-maximized-changed', false)
  })

  if (isDev) {
    mainWindow.loadURL('http://127.0.0.1:5173')
  } else {
    mainWindow.loadFile(webIndexHtml())
  }

  mainWindow.webContents.on('did-finish-load', () => {
    if (backendIsReady) {
      mainWindow.webContents.send('backend-ready')
    }
    if (pendingOAuthUrl) {
      mainWindow.webContents.send('oauth-callback', pendingOAuthUrl)
      pendingOAuthUrl = null
    }
  })

  mainWindow.on('close', (e) => {
    if (quitRequested) return
    e.preventDefault()
    mainWindow.hide()
  })
}

function setupAutoLaunch(appName, silent = false) {
  const args = silent ? ['--silent'] : []
  return new AutoLaunch({
    name: appName || 'XBClient',
    path: app.getPath('exe'),
    args,
    isHidden: silent,
  })
}

function createTrayIcon() {
  const icon = loadAppIcon()
  if (!icon.isEmpty()) {
    return icon.resize({ width: 16, height: 16 })
  }

  const width = 16
  const height = 16
  const r = 0x38
  const g = 0xbd
  const b = 0xf8
  const a = 0xff

  const rowSize = width * 4
  const pixelDataSize = rowSize * height
  const fileHeaderSize = 14
  const infoHeaderSize = 40
  const fileSize = fileHeaderSize + infoHeaderSize + pixelDataSize

  const buffer = Buffer.alloc(fileSize)
  let offset = 0

  buffer.writeUInt8(0x42, offset++)
  buffer.writeUInt8(0x4d, offset++)
  buffer.writeUInt32LE(fileSize, offset)
  offset += 4
  buffer.writeUInt16LE(0, offset)
  offset += 2
  buffer.writeUInt16LE(0, offset)
  offset += 2
  buffer.writeUInt32LE(fileHeaderSize + infoHeaderSize, offset)
  offset += 4

  buffer.writeUInt32LE(infoHeaderSize, offset)
  offset += 4
  buffer.writeInt32LE(width, offset)
  offset += 4
  buffer.writeInt32LE(height, offset)
  offset += 4
  buffer.writeUInt16LE(1, offset)
  offset += 2
  buffer.writeUInt16LE(32, offset)
  offset += 2
  buffer.writeUInt32LE(0, offset)
  offset += 4
  buffer.writeUInt32LE(pixelDataSize, offset)
  offset += 4
  buffer.writeInt32LE(2835, offset)
  offset += 4
  buffer.writeInt32LE(2835, offset)
  offset += 4
  buffer.writeUInt32LE(0, offset)
  offset += 4
  buffer.writeUInt32LE(0, offset)
  offset += 4

  for (let y = height - 1; y >= 0; y--) {
    for (let x = 0; x < width; x++) {
      buffer.writeUInt8(b, offset++)
      buffer.writeUInt8(g, offset++)
      buffer.writeUInt8(r, offset++)
      buffer.writeUInt8(a, offset++)
    }
  }

  return nativeImage.createFromBuffer(buffer, 'image/bmp')
}

function parseSocksAddr(addr) {
  const idx = addr.lastIndexOf(':')
  if (idx <= 0) throw new Error(`Invalid SOCKS address: ${addr}`)
  const port = Number(addr.slice(idx + 1))
  if (!Number.isFinite(port) || port <= 0) throw new Error(`Invalid SOCKS port: ${addr}`)
  return { host: addr.slice(0, idx), port }
}

function dnsAddressForVpn(value) {
  const dns = value.trim()
  if (/^[0-9.]+$/.test(dns) || (/^[0-9A-Fa-f:.]+$/.test(dns) && dns.includes(':'))) return dns
  const lower = dns.toLowerCase()
  if (lower.includes('cloudflare-dns.com') || lower.includes('1.1.1.1')) return '1.1.1.1'
  if (lower.includes('dns.alidns.com') || lower.includes('223.5.5.5')) return '223.5.5.5'
  throw new Error('海外 DNS 需填写普通 DNS 地址，或已支持的 DoH 地址。')
}

function aerionNodeWithResolvedHost(rawJson, resolvedHost) {
  const raw = JSON.parse(rawJson)
  const originalHost = String(raw.host ?? raw.server ?? '')
  if (resolvedHost !== originalHost && !String(raw.sni ?? '').trim()) raw.sni = originalHost
  raw.host = resolvedHost
  if (raw.server !== undefined) raw.server = resolvedHost
  if (raw.address !== undefined) raw.address = resolvedHost
  return raw
}

function trayNodeLabel(node, index) {
  const name = String(node.name ?? '').trim()
  if (!name || name === node.host || name === `${node.host}:${node.port}` || name.includes(node.host)) {
    return `节点 ${index + 1}`
  }
  return name
}

function trayDesktopProxySupported() {
  return ELECTRON_SUPPORTED_PLATFORMS.has(process.platform)
}

function pushTrayStateToWeb(patch) {
  if (mainWindow && !mainWindow.isDestroyed()) {
    mainWindow.webContents.send('tray-state-from-main', patch)
  }
}

function applyTraySnapshot(snapshot) {
  if (!snapshot || typeof snapshot !== 'object') return
  trayState.nodes = Array.isArray(snapshot.nodes) ? snapshot.nodes : []
  trayState.selectedNodeIndex = Number.isFinite(snapshot.selectedNodeIndex) ? snapshot.selectedNodeIndex : 0
  trayState.vpn =
    snapshot.vpn && typeof snapshot.vpn.sessionId === 'number'
      ? {
          sessionId: snapshot.vpn.sessionId,
          socksAddr: String(snapshot.vpn.socksAddr ?? ''),
          nodeIndex: Number(snapshot.vpn.nodeIndex ?? 0),
        }
      : null
  trayState.systemProxyOn = Boolean(snapshot.systemProxyOn)
  trayState.useVpn = snapshot.useVpn !== false
  trayState.routingMode = 'rule'
  if (snapshot.settings && typeof snapshot.settings === 'object') {
    Object.assign(trayState.settings, snapshot.settings)
    if (snapshot.settings.routingMode === 'global' || snapshot.settings.routingMode === 'direct') {
      trayState.routingMode = snapshot.settings.routingMode
    }
    if (typeof snapshot.settings.tunEnabled === 'boolean') {
      trayState.useVpn = snapshot.settings.tunEnabled
    }
  }
  if (snapshot.routingMode === 'global' || snapshot.routingMode === 'direct') {
    trayState.routingMode = snapshot.routingMode
  }
  trayState.userAgent = String(snapshot.userAgent ?? '')
  activeVpnSessionId = trayState.vpn?.sessionId ?? null
  rebuildTrayMenu()
}

async function trayResolveNode(node) {
  const raw = JSON.parse(node.rawJson)
  const host = String(raw.host ?? raw.server ?? node.host)
  const resolvedHost = await backendInvoke('resolve_node_host', {
    dnsUrl: trayState.settings.nodeDns,
    host,
    userAgent: trayState.userAgent,
  })
  return aerionNodeWithResolvedHost(node.rawJson, resolvedHost)
}

async function trayDisconnect() {
  if (!trayState.vpn) return
  if (trayState.useVpn) {
    await backendInvoke('aerion_stop_vpn', { sessionId: trayState.vpn.sessionId })
    activeVpnSessionId = null
  } else {
    await backendInvoke('aerion_stop', { sessionId: trayState.vpn.sessionId })
    if (trayState.systemProxyOn) {
      await backendInvoke('system_proxy_clear', {})
      trayState.systemProxyOn = false
    }
  }
  trayState.vpn = null
  pushTrayStateToWeb({ vpn: null, systemProxyOn: trayState.systemProxyOn })
}

async function trayConnect(index) {
  if (trayState.routingMode === 'direct') return
  const node = trayState.nodes[index]
  if (!node?.connectSupported) throw new Error('当前节点协议不支持连接')
  const resolved = await trayResolveNode(node)
  if (trayState.useVpn) {
    const dnsMode = trayState.settings.vpnDnsMode
    const dnsSource = dnsMode === 'direct' ? trayState.settings.directDns : trayState.settings.overseasDns
    const handle = await backendInvoke('aerion_start_vpn', {
      node: resolved,
      mtu: 1500,
      dns: dnsMode,
      dns_addr: dnsAddressForVpn(dnsSource),
      virtual_dns_pool: trayState.settings.virtualDnsPool,
      ipv6: trayState.settings.vpnIpv6Enabled,
    })
    trayState.vpn = { sessionId: handle.session_id, socksAddr: '', nodeIndex: index }
    activeVpnSessionId = handle.session_id
  } else {
    const handle = await backendInvoke('aerion_start_socks', { node: resolved })
    trayState.vpn = { sessionId: handle.session_id, socksAddr: handle.socks_addr, nodeIndex: index }
    if (trayState.systemProxyOn) {
      const parsed = parseSocksAddr(handle.socks_addr)
      await backendInvoke('system_proxy_set', { host: parsed.host, port: parsed.port })
    }
  }
  trayState.selectedNodeIndex = index
  pushTrayStateToWeb({
    selectedNodeIndex: index,
    vpn: trayState.vpn,
    systemProxyOn: trayState.systemProxyOn,
  })
}

async function traySetRoutingMode(mode) {
  if (trayBusy) return
  trayBusy = true
  rebuildTrayMenu()
  try {
    if (!backendIsReady) await waitBackendReady()
    const wasDirect = trayState.routingMode === 'direct'
    trayState.routingMode = mode
    pushTrayStateToWeb({ settings: { routingMode: mode } })
    if (mode === 'direct') {
      if (trayState.vpn) await trayDisconnect()
      return
    }
    if (wasDirect || !trayState.vpn) await trayConnect(trayState.selectedNodeIndex)
  } catch (err) {
    const message = err instanceof Error ? err.message : String(err)
    dialog.showErrorBox('路由模式', message)
    throw err
  } finally {
    trayBusy = false
    rebuildTrayMenu()
  }
}

async function trayToggleVpn() {
  if (trayBusy) return
  trayBusy = true
  rebuildTrayMenu()
  try {
    if (!backendIsReady) await waitBackendReady()
    trayState.useVpn = !trayState.useVpn
    pushTrayStateToWeb({ settings: { tunEnabled: trayState.useVpn } })
    if (trayState.routingMode === 'direct') return
    if (!trayState.useVpn) {
      if (trayState.vpn && !trayState.vpn.socksAddr) await trayDisconnect()
      return
    }
    if (trayState.vpn?.socksAddr) {
      await trayDisconnect()
      await trayConnect(trayState.selectedNodeIndex)
      return
    }
    if (trayState.vpn) await trayDisconnect()
    else await trayConnect(trayState.selectedNodeIndex)
  } catch (err) {
    const message = err instanceof Error ? err.message : String(err)
    dialog.showErrorBox('TUN 模式', message)
    throw err
  } finally {
    trayBusy = false
    rebuildTrayMenu()
  }
}

async function trayToggleSystemProxy() {
  if (trayBusy) return
  trayBusy = true
  rebuildTrayMenu()
  try {
    if (!backendIsReady) await waitBackendReady()
    if (trayState.systemProxyOn) {
      await backendInvoke('system_proxy_clear', {})
      trayState.systemProxyOn = false
      pushTrayStateToWeb({ systemProxyOn: false, settings: { systemProxyEnabled: false } })
    } else {
      const socksAddr = trayState.vpn?.socksAddr
      if (!socksAddr) throw new Error('请先关闭 TUN 并建立 SOCKS 会话，或连接后带有本地 SOCKS 地址')
      const parsed = parseSocksAddr(socksAddr)
      await backendInvoke('system_proxy_set', { host: parsed.host, port: parsed.port })
      trayState.systemProxyOn = true
      pushTrayStateToWeb({ systemProxyOn: true, settings: { systemProxyEnabled: true } })
    }
  } catch (err) {
    const message = err instanceof Error ? err.message : String(err)
    dialog.showErrorBox('系统代理', message)
    throw err
  } finally {
    trayBusy = false
    rebuildTrayMenu()
  }
}

async function traySelectNode(index) {
  if (trayBusy) return
  if (index < 0 || index >= trayState.nodes.length) return
  const node = trayState.nodes[index]
  if (!node.connectSupported) {
    dialog.showErrorBox('选择节点', '当前节点协议不支持连接')
    return
  }
  if (index === trayState.selectedNodeIndex && !trayState.vpn) {
    trayState.selectedNodeIndex = index
    pushTrayStateToWeb({ selectedNodeIndex: index })
    rebuildTrayMenu()
    return
  }

  trayBusy = true
  rebuildTrayMenu()
  try {
    if (!backendIsReady) await waitBackendReady()
    const wasConnected = Boolean(trayState.vpn)
    if (wasConnected) await trayDisconnect()
    trayState.selectedNodeIndex = index
    if (wasConnected) await trayConnect(index)
    else pushTrayStateToWeb({ selectedNodeIndex: index })
  } catch (err) {
    const message = err instanceof Error ? err.message : String(err)
    dialog.showErrorBox('选择节点', message)
    throw err
  } finally {
    trayBusy = false
    rebuildTrayMenu()
  }
}

function rebuildTrayMenu() {
  if (!trayInstance) return

  const connected = Boolean(trayState.vpn)
  const currentNode = trayState.nodes[trayState.selectedNodeIndex]
  const nodeName = currentNode ? trayNodeLabel(currentNode, trayState.selectedNodeIndex) : '无节点'
  const proxySupported = trayDesktopProxySupported()
  const proxySocksReady = Boolean(trayState.vpn?.socksAddr)
  const routingLabels = { rule: '规则', global: '全局', direct: '直连' }

  const template = [
    { label: '显示窗口', click: () => showMainWindow() },
    { type: 'separator' },
    {
      label: '路由模式',
      submenu: ['rule', 'global', 'direct'].map((mode) => ({
        label: routingLabels[mode],
        type: 'radio',
        checked: trayState.routingMode === mode,
        enabled: !trayBusy,
        click: () => {
          void traySetRoutingMode(mode)
        },
      })),
    },
    {
      label: 'TUN 模式',
      type: 'checkbox',
      checked: trayState.useVpn,
      enabled: !trayBusy && trayState.routingMode !== 'direct',
      click: () => {
        void trayToggleVpn()
      },
    },
  ]

  if (proxySupported) {
    template.push({
      label: '系统代理',
      type: 'checkbox',
      checked: trayState.systemProxyOn,
      enabled: !trayBusy && !trayState.useVpn && trayState.routingMode !== 'direct' && (!trayState.systemProxyOn ? proxySocksReady : true),
      click: () => {
        void trayToggleSystemProxy()
      },
    })
  }

  const nodeItems = trayState.nodes.map((node, index) => ({
    label: trayNodeLabel(node, index),
    type: 'radio',
    checked: index === trayState.selectedNodeIndex,
    enabled: !trayBusy && node.connectSupported,
    click: () => {
      void traySelectNode(index)
    },
  }))

  template.push(
    { type: 'separator' },
    {
      label: `当前节点：${nodeName}`,
      enabled: false,
    },
    {
      label: '选择节点',
      enabled: nodeItems.length > 0 && !trayBusy,
      submenu: nodeItems.length ? nodeItems : [{ label: '无可用节点', enabled: false }],
    },
    { type: 'separator' },
    {
      label: '退出',
      click: () => {
        quitRequested = true
        app.quit()
      },
    },
  )

  trayInstance.setContextMenu(Menu.buildFromTemplate(template))
  const status = [routingLabels[trayState.routingMode] || '规则', connected ? '已连接' : '未连接', nodeName]
  if (trayState.useVpn) status.push('TUN')
  if (trayState.systemProxyOn) status.push('系统代理')
  trayInstance.setToolTip(`${app.getName()} · ${status.join(' · ')}`)
}

function setupTray(appName) {
  const trayIcon = createTrayIcon()
  trayInstance = new Tray(trayIcon)
  trayInstance.setToolTip(appName)
  rebuildTrayMenu()
  trayInstance.on('click', () => showMainWindow())
}

function showMainWindow() {
  if (!mainWindow) {
    createMainWindow()
    return
  }
  mainWindow.show()
  mainWindow.restore()
  mainWindow.focus()
}

function startHttpHandlers() {
  ipcMain.handle('electron-get-version', () => app.getVersion())
  ipcMain.handle('electron-get-runtime-config', () => {
    validateBackendEnv()
    return desktopRuntimeConfig()
  })
  ipcMain.handle('electron-get-runtime-capabilities', () => desktopRuntimeCapabilities())
  ipcMain.handle('electron-open-external', (_, { url }) => shell.openExternal(url))
  ipcMain.handle('electron-open-inapp-browser', (_, { url, title }) => {
    const win = new BrowserWindow({
      width: 1024,
      height: 720,
      minWidth: 720,
      minHeight: 520,
      title: title || 'Browser',
      webPreferences: {
        contextIsolation: true,
        nodeIntegration: false,
      },
    })
    oauthBrowserWindows.add(win)
    win.on('closed', () => oauthBrowserWindows.delete(win))
    win.loadURL(url)
    return true
  })

  ipcMain.handle('electron-oauth-take-callback', () => {
    const url = pendingOAuthUrl
    pendingOAuthUrl = null
    return url
  })

  ipcMain.on('electron-launched-silent', (event) => {
    event.returnValue = launchedSilentArgv()
  })

  ipcMain.handle('electron-hide-main-window', () => {
    if (mainWindow && !mainWindow.isDestroyed()) mainWindow.hide()
    return true
  })

  ipcMain.handle('electron-window-minimize', () => {
    if (mainWindow && !mainWindow.isDestroyed()) mainWindow.minimize()
    return true
  })

  ipcMain.handle('electron-window-maximize', () => {
    if (!mainWindow || mainWindow.isDestroyed()) return false
    if (mainWindow.isMaximized()) mainWindow.unmaximize()
    else mainWindow.maximize()
    return mainWindow.isMaximized()
  })

  ipcMain.handle('electron-window-close', () => {
    if (mainWindow && !mainWindow.isDestroyed()) mainWindow.close()
    return true
  })

  ipcMain.handle('electron-window-is-maximized', () => {
    if (!mainWindow || mainWindow.isDestroyed()) return false
    return mainWindow.isMaximized()
  })

  ipcMain.handle('electron-autostart-is-enabled', async () => autoLauncherInstance?.isEnabled() ?? false)
  ipcMain.handle('electron-autostart-set-enabled', async (_, { value, silent }) => {
    autoLauncherInstance = setupAutoLaunch(app.getName(), Boolean(silent))
    if (value) await autoLauncherInstance.enable()
    else await autoLauncherInstance.disable()
    return true
  })

  ipcMain.handle('electron-invoke', async (_, { cmd, args }) => {
    await ensureBackendRunning()
    return backendInvoke(cmd, args)
  })

  ipcMain.handle('electron-report-vpn-session', (_, { sessionId }) => {
    activeVpnSessionId = typeof sessionId === 'number' ? sessionId : null
  })

  ipcMain.handle('electron-sync-tray-state', (_, { state }) => {
    applyTraySnapshot(state)
  })
}

function notifyBackendError(err) {
  const message = err instanceof Error ? err.message : String(err)
  console.error('[backend]', message)
  dialog.showErrorBox('启动失败', message)
  if (mainWindow && !mainWindow.isDestroyed()) {
    mainWindow.webContents.send('backend-error', message)
  }
}

async function startBackendInBackground() {
  try {
    if (isDev) await startViteDevServer()
    await ensureBackendRunning()
  } catch (err) {
    notifyBackendError(err)
  }
}

if (instanceLock) {
  app.whenReady().then(async () => {
    const localProps = readLocalProperties()
    const appName = process.env.XBCLIENT_APP_NAME || localProps['xbclient.appName'] || 'XBClient'
    app.setName(appName)
    if (!ELECTRON_SUPPORTED_PLATFORMS.has(process.platform)) {
      await dialog.showMessageBox({
        type: 'warning',
        title: appName,
        message: '当前 Electron 桌面端仅支持 Windows 与 Linux。macOS 版本尚未发布，移动端请使用 Android 客户端。',
        buttons: ['退出'],
      })
      app.quit()
      return
    }
    if (process.platform === 'win32') {
      app.setAppUserModelId('moe.telecom.xbclient')
    }
    autoLauncherInstance = setupAutoLaunch(appName)

    handleOAuthArgv(process.argv)

    startHttpHandlers()
    createMainWindow()
    setupTray(appName)
    setupAutoUpdater()
    void startBackendInBackground()

    app.on('activate', () => {
      if (BrowserWindow.getAllWindows().length === 0) createMainWindow()
    })
  })

  app.on('window-all-closed', () => {
    if (process.platform !== 'darwin') app.quit()
  })

  app.on('before-quit', () => {
    quitRequested = true
    if (activeVpnSessionId != null) {
      const stopCmd = trayState.useVpn ? 'aerion_stop_vpn' : 'aerion_stop'
      backendInvoke(stopCmd, { sessionId: activeVpnSessionId }).catch(() => {})
      activeVpnSessionId = null
    }
    if (trayState.systemProxyOn) backendInvoke('system_proxy_clear', {}).catch(() => {})
  })

  process.on('exit', () => {
    if (backendProc && !backendProc.killed) backendProc.kill('SIGTERM')
    if (viteProc && !viteProc.killed) viteProc.kill('SIGTERM')
  })
}
