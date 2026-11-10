import { execFile } from 'node:child_process'
import { readFile } from 'node:fs/promises'
import { promisify } from 'node:util'
import { startHttpProxy } from './http-proxy.js'

const execute = promisify(execFile)
let bridge: Awaited<ReturnType<typeof startHttpProxy>> | null = null
let changes: Promise<void> = Promise.resolve()

function updateProxy(change: () => Promise<void>) {
  const next = changes.then(change)
  changes = next.catch(() => {})
  return next
}

async function windowsProxy(action: 'set' | 'clear', port = 0) {
  const source = await readFile(new URL('../src/windows-proxy.ps1', import.meta.url), 'utf8')
  const command = `$Action = '${action}'\n$Port = ${port}\n${source}`
  await execute('powershell.exe', ['-NoProfile', '-NonInteractive', '-WindowStyle', 'Hidden', '-EncodedCommand', Buffer.from(command, 'utf16le').toString('base64')], { windowsHide: true })
}

export function setWindowsSystemProxy(host: string, port: number) {
  return updateProxy(async () => {
    const next = await startHttpProxy(host, port)
    try {
      await windowsProxy('set', next.port)
    } catch (error) {
      await next.close()
      await windowsProxy('clear')
      throw error
    }
    const previous = bridge
    bridge = next
    await previous?.close()
  })
}

export function clearWindowsSystemProxy() {
  return updateProxy(async () => {
    await windowsProxy('clear')
    const previous = bridge
    bridge = null
    await previous?.close()
  })
}
