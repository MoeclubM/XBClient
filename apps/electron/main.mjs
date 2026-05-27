import { app, BrowserWindow, dialog, ipcMain, shell, Tray, Menu, nativeImage } from 'electron'
import path from 'node:path'
import { spawn } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import readline from 'node:readline'
import fs from 'node:fs'
import AutoLaunch from 'auto-launch'
import { setupAutoUpdater } from './updater.mjs'

const __filename = fileURLToPath(import.meta.url)
const __dirname = path.dirname(__filename)
const repoRoot = path.resolve(__dirname, '../..')

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
  if (!['win32', 'linux'].includes(process.platform)) return
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

function desktopRuntimeCapabilities() {
  const desktop = process.platform === 'win32' || process.platform === 'linux'
  return {
    platform: process.platform === 'win32' ? 'windows' : process.platform,
    system_proxy: false,
    oauth_callback: desktop,
    autostart: desktop,
    tray: desktop,
    local_socks: true,
    vpn: desktop,
    payment: true,
    admob: false,
  }
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
  const env = validateBackendEnv()
  const releaseBinary = backendReleaseBinaryPath()

  if (isDev) {
    backendProc = spawn('cargo', ['run', '--quiet', '--manifest-path', backendManifestPath()], {
      cwd: repoRoot,
      env,
      stdio: ['pipe', 'pipe', 'pipe'],
    })
  } else {
    if (!fs.existsSync(releaseBinary)) {
      throw new Error(
        `未找到 electron-backend：${releaseBinary}\n请先运行：pnpm --filter xbclient-electron build`,
      )
    }
    backendProc = spawn(releaseBinary, [], {
      cwd: path.dirname(releaseBinary),
      env,
      stdio: ['pipe', 'pipe', 'pipe'],
      windowsHide: true,
    })
  }

  backendProc.on('exit', (code) => {
    const error = new Error(`electron-backend exited with code ${code}`)
    for (const [, pending] of backendPending.entries()) pending.reject(error)
    backendPending.clear()
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
    console.error('[backend:stderr]', String(data).trim())
  })
}

async function waitBackendReady() {
  const deadline = Date.now() + 120_000
  while (Date.now() < deadline) {
    try {
      await backendInvoke('runtime_capabilities', {})
      return
    } catch {
      await new Promise((resolve) => setTimeout(resolve, 200))
    }
  }
  throw new Error('electron-backend 启动超时')
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
  mainWindow = new BrowserWindow({
    width: 1024,
    height: 720,
    minWidth: 800,
    minHeight: 600,
    ...(icon.isEmpty() ? {} : { icon }),
    webPreferences: {
      preload: path.resolve(__dirname, 'preload.cjs'),
      contextIsolation: true,
      nodeIntegration: false,
    },
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

function setupAutoLaunch(appName) {
  return new AutoLaunch({
    name: appName || 'XBClient',
    path: app.getPath('exe'),
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

function setupTray(appName) {
  const trayIcon = createTrayIcon()
  const tray = new Tray(trayIcon)
  tray.setToolTip(appName)

  const menu = Menu.buildFromTemplate([
    { label: '显示窗口', click: () => showMainWindow() },
    { type: 'separator' },
    {
      label: '退出',
      click: () => {
        quitRequested = true
        app.quit()
      },
    },
  ])
  tray.setContextMenu(menu)
  tray.on('click', () => showMainWindow())
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

function startHttpHandlers(autoLauncher) {
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

  ipcMain.handle('electron-autostart-is-enabled', async () => autoLauncher.isEnabled())
  ipcMain.handle('electron-autostart-set-enabled', async (_, { value }) => {
    if (value) await autoLauncher.enable()
    else await autoLauncher.disable()
    return true
  })

  ipcMain.handle('electron-invoke', async (_, { cmd, args }) => {
    if (!backendIsReady && cmd !== 'runtime_capabilities' && cmd !== 'runtime_config') {
      await waitBackendReady()
    }
    return backendInvoke(cmd, args)
  })

  ipcMain.handle('electron-report-vpn-session', (_, { sessionId }) => {
    activeVpnSessionId = typeof sessionId === 'number' ? sessionId : null
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
    backendStart()
    await waitBackendReady()
    notifyBackendReady()
  } catch (err) {
    notifyBackendError(err)
  }
}

if (instanceLock) {
  app.whenReady().then(async () => {
    const localProps = readLocalProperties()
    const appName = process.env.XBCLIENT_APP_NAME || localProps['xbclient.appName'] || 'XBClient'
    app.setName(appName)
    if (process.platform === 'win32') {
      app.setAppUserModelId('moe.telecom.xbclient')
    }
    const autoLauncher = setupAutoLaunch(appName)

    handleOAuthArgv(process.argv)

    startHttpHandlers(autoLauncher)
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
      backendInvoke('aerion_stop_vpn', { sessionId: activeVpnSessionId }).catch(() => {})
      activeVpnSessionId = null
    }
    backendInvoke('system_proxy_clear', {}).catch(() => {})
  })

  process.on('exit', () => {
    if (backendProc && !backendProc.killed) backendProc.kill('SIGTERM')
    if (viteProc && !viteProc.killed) viteProc.kill('SIGTERM')
  })
}
