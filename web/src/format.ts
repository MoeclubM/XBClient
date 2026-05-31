export function numericValue(value: unknown): number {
  if (value === undefined || value === null) throw new Error('numeric value is required')
  if (typeof value === 'number') {
    if (!Number.isFinite(value)) throw new Error('numeric value is not finite')
    return value
  }
  if (typeof value === 'string') {
    const parsed = Number(value)
    if (!Number.isFinite(parsed)) throw new Error(`numeric value is invalid: ${value}`)
    return parsed
  }
  throw new Error(`numeric value has unsupported type: ${typeof value}`)
}

export function formatMoney(amountCents: number, symbol: string, unit: string): string {
  const text = symbol + (amountCents / 100).toFixed(2) + (unit ? ` ${unit}` : '')
  return text.trim()
}

export function formatTrafficGb(value: number): string {
  if (value >= 1024) return `${(value / 1024).toFixed(2)} TB`
  return `${Math.round(value)} GB`
}

export function formatTrafficBytes(value: number): string {
  if (value <= 0) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  let size = value
  let index = 0
  while (size >= 1024 && index < units.length - 1) {
    size /= 1024
    index++
  }
  return `${size.toFixed(2)} ${units[index]}`
}

export function formatUnixDate(seconds: number): string {
  if (seconds <= 0) return ''
  const d = new Date(seconds * 1000)
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}

export function formatUnixDateTime(seconds: number): string {
  if (seconds <= 0) return ''
  const d = new Date(seconds * 1000)
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

export function formatDuration(durationMs: number): string {
  const seconds = Math.floor(durationMs / 1000)
  const h = Math.floor(seconds / 3600)
  const m = Math.floor((seconds % 3600) / 60)
  const s = seconds % 60
  if (h > 0) return `${h}:${pad(m)}:${pad(s)}`
  return `${pad(m)}:${pad(s)}`
}

export function publicErrorText(value: unknown, defaultMessage = 'Operation failed'): string {
  const raw = value instanceof Error ? value.message : String(value ?? '')
  const text = raw
    .replace(/not allowed to open url .*/i, 'Unable to open link')
    .replace(/https?:\/\/[^\s"'<>，。)]+/gi, 'service address')
    .trim()
  return text || defaultMessage
}

function pad(value: number): string {
  return value.toString().padStart(2, '0')
}
