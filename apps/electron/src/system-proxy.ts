import { execFile } from 'node:child_process'
import { readFile } from 'node:fs/promises'
import { promisify } from 'node:util'
import { startHttpProxy, type HttpProxyBridge } from './http-proxy.js'

export const DEFAULT_SYSTEM_PROXY_BYPASS =
  '<local>;localhost;127.*;10.*;172.16.*;172.17.*;172.18.*;172.19.*;172.2*;172.30.*;172.31.*;192.168.*;[::1]'

export interface LocalProxySyncOptions {
  host: string
  port: number
  enableSystemProxy: boolean
  listenPort?: number
  allowLan?: boolean
  bypass?: string
  setPlatformProxy?: (host: string, port: number) => Promise<void>
  clearPlatformProxy?: () => Promise<void>
}

const execute = promisify(execFile)
let bridge: HttpProxyBridge | null = null
let windowsProxyApplied = false
let appliedPort = 0
let appliedBypass = ''
let changes: Promise<void> = Promise.resolve()

function updateProxy<T>(change: () => Promise<T>): Promise<T> {
  const next = changes.then(change)
  changes = next.then(() => {}, () => {})
  return next
}

async function windowsProxy(action: 'set' | 'clear', port = 0, bypass = DEFAULT_SYSTEM_PROXY_BYPASS) {
  const source = await readFile(new URL('../src/windows-proxy.ps1', import.meta.url), 'utf8')
  const escapedBypass = bypass.replace(/'/g, "''")
  const command = `$Action = '${action}'\n$Port = ${port}\n$Bypass = '${escapedBypass}'\n${source}`
  await execute('powershell.exe', ['-NoProfile', '-NonInteractive', '-WindowStyle', 'Hidden', '-EncodedCommand', Buffer.from(command, 'utf16le').toString('base64')], { windowsHide: true })
}

export function syncDesktopLocalProxy(options: LocalProxySyncOptions): Promise<{ httpPort: number; systemProxyOn: boolean }> {
  return updateProxy(async () => {
    const listenPort = options.listenPort && options.listenPort > 0 ? options.listenPort : 0
    const allowLan = Boolean(options.allowLan)
    const bypass = (options.bypass ?? '').trim() || DEFAULT_SYSTEM_PROXY_BYPASS

    if (bridge && bridge.listenPort === listenPort && bridge.allowLan === allowLan) {
      bridge.setUpstream(options.host, options.port)
    } else {
      if (bridge && listenPort > 0) {
        const previous = bridge
        bridge = null
        await previous.close()
      }
      const next = await startHttpProxy(options.host, options.port, { listenPort, allowLan })
      const previous = bridge
      bridge = next
      await previous?.close()
    }

    try {
      if (process.platform === 'win32') {
        if (options.enableSystemProxy) {
          if (!windowsProxyApplied || appliedPort !== bridge.port || appliedBypass !== bypass) {
            await windowsProxy('set', bridge.port, bypass)
            windowsProxyApplied = true
            appliedPort = bridge.port
            appliedBypass = bypass
          }
        } else if (windowsProxyApplied) {
          await windowsProxy('clear')
          windowsProxyApplied = false
          appliedPort = 0
          appliedBypass = ''
        }
      } else if (options.enableSystemProxy) {
        await options.setPlatformProxy?.(options.host, options.port)
      } else {
        await options.clearPlatformProxy?.()
      }
    } catch (error) {
      const failed = bridge
      bridge = null
      await failed?.close()
      if (process.platform === 'win32') {
        await windowsProxy('clear')
        windowsProxyApplied = false
        appliedPort = 0
        appliedBypass = ''
      } else {
        await options.clearPlatformProxy?.()
      }
      throw error
    }
    return {
      httpPort: bridge.port,
      systemProxyOn: options.enableSystemProxy,
    }
  })
}

export function clearDesktopLocalProxy(clearPlatformProxy?: () => Promise<void>): Promise<void> {
  return updateProxy(async () => {
    if (process.platform === 'win32') {
      await windowsProxy('clear')
      windowsProxyApplied = false
      appliedPort = 0
      appliedBypass = ''
    } else {
      await clearPlatformProxy?.()
    }
    const previous = bridge
    bridge = null
    await previous?.close()
  })
}

export function setWindowsSystemProxy(
  host: string,
  port: number,
  options?: { listenPort?: number; allowLan?: boolean; bypass?: string },
): Promise<void> {
  return syncDesktopLocalProxy({
    host,
    port,
    enableSystemProxy: true,
    listenPort: options?.listenPort,
    allowLan: options?.allowLan,
    bypass: options?.bypass,
  }).then(() => {})
}

export function clearWindowsSystemProxy(): Promise<void> {
  return clearDesktopLocalProxy()
}
