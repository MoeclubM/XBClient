import { ref } from 'vue'
import {
  clearLogs as clearElectronLogs,
  getLogs,
  onAerionLog,
  onAeronEvent,
  type AerionLogEntry,
} from '../platform/electron'
import { isElectronShell } from '../platform/shell'
import { useAppStore } from '../store'

export const logsPaused = ref(false)
const pausedBuffer: AerionLogEntry[] = []
let installed = false

export function setLogsPaused(paused: boolean): void {
  logsPaused.value = paused
  if (!paused && pausedBuffer.length > 0) {
    const store = useAppStore.getState()
    for (const item of pausedBuffer.splice(0)) store.appendLog(item)
  }
}

export async function clearAllLogs(): Promise<void> {
  pausedBuffer.length = 0
  useAppStore.getState().setLogs([])
  if (isElectronShell()) await clearElectronLogs()
}

export function installDesktopTelemetry(): () => void {
  if (!isElectronShell() || installed) return () => {}
  installed = true

  let trackedWrapperId: number | null = null
  let lastUpload = 0
  let lastDownload = 0
  let lastTickAt = Date.now()
  const openCoreSessions = new Set<number>()

  void getLogs().then((entries) => {
    useAppStore.getState().setLogs(entries)
  }).catch((error) => {
    console.error('load initial backend logs failed', error)
  })

  const unlistenLog = onAerionLog((entry) => {
    if (logsPaused.value) {
      pausedBuffer.push(entry)
      if (pausedBuffer.length > 500) pausedBuffer.splice(0, pausedBuffer.length - 500)
      return
    }
    useAppStore.getState().appendLog(entry)
  })

  const unlistenEvent = onAeronEvent((payload) => {
    try {
      const data = JSON.parse(payload) as {
        type?: string
        wrapper_session_id?: number
        session_id?: number
        upload_bytes?: number
        download_bytes?: number
      }
      const state = useAppStore.getState()
      const currentId = state.vpn?.sessionId ?? null
      if (currentId === null || data.wrapper_session_id !== currentId) return
      if (trackedWrapperId !== currentId) {
        trackedWrapperId = currentId
        lastUpload = 0
        lastDownload = 0
        lastTickAt = Date.now()
        openCoreSessions.clear()
      }
      if (data.type === 'traffic_recorded') {
        state.updateVpnTraffic(currentId, Number(data.upload_bytes) || 0, Number(data.download_bytes) || 0)
        return
      }
      if (typeof data.session_id === 'number') {
        if (data.type === 'session_opened') {
          openCoreSessions.add(data.session_id)
          state.setActiveSessions(openCoreSessions.size)
        } else if (data.type === 'session_closed' || data.type === 'session_cancelled') {
          openCoreSessions.delete(data.session_id)
          state.setActiveSessions(openCoreSessions.size)
        }
      }
    } catch (error) {
      console.error('parse Aerion event failed', error)
    }
  })

  const speedTimer = window.setInterval(() => {
    const state = useAppStore.getState()
    const vpn = state.vpn
    const now = Date.now()
    if (!vpn) {
      trackedWrapperId = null
      lastUpload = 0
      lastDownload = 0
      lastTickAt = now
      if (openCoreSessions.size > 0) openCoreSessions.clear()
      if (state.uploadSpeedBps !== 0 || state.downloadSpeedBps !== 0) state.setLiveSpeed(0, 0)
      if (state.activeSessions !== 0) state.setActiveSessions(0)
      return
    }
    if (trackedWrapperId !== vpn.sessionId) {
      trackedWrapperId = vpn.sessionId
      lastUpload = vpn.uploadBytes
      lastDownload = vpn.downloadBytes
      lastTickAt = now
      openCoreSessions.clear()
      state.setLiveSpeed(0, 0)
      state.setActiveSessions(0)
      return
    }
    const elapsedSec = Math.max(0.25, (now - lastTickAt) / 1000)
    const upBps = Math.max(0, Math.round((vpn.uploadBytes - lastUpload) / elapsedSec))
    const downBps = Math.max(0, Math.round((vpn.downloadBytes - lastDownload) / elapsedSec))
    lastUpload = vpn.uploadBytes
    lastDownload = vpn.downloadBytes
    lastTickAt = now
    if (upBps !== state.uploadSpeedBps || downBps !== state.downloadSpeedBps) {
      state.setLiveSpeed(upBps, downBps)
    }
  }, 1000)

  return () => {
    installed = false
    unlistenLog()
    unlistenEvent()
    window.clearInterval(speedTimer)
  }
}
