import type { OAuthProvider } from '../store'

export type Row = Record<string, unknown>

export function dataRows(value: unknown): Row[] {
  if (Array.isArray(value)) return value as Row[]
  if (value && typeof value === 'object') {
    const object = value as Row
    for (const key of ['data', 'list', 'items', 'sessions', 'tickets', 'logs', 'codes', 'records']) {
      if (Array.isArray(object[key])) return object[key] as Row[]
      if (object[key] && typeof object[key] === 'object') {
        const nested = object[key] as Row
        for (const nestedKey of ['data', 'list', 'items', 'sessions', 'tickets', 'logs', 'codes', 'records']) {
          if (Array.isArray(nested[nestedKey])) return nested[nestedKey] as Row[]
        }
      }
    }
  }
  return []
}

export function field(row: Row, keys: string[]): string {
  for (const key of keys) {
    const value = row[key]
    if (value !== undefined && value !== null && String(value).trim()) return String(value)
  }
  return ''
}

export function rowId(row: Row): string {
  return field(row, ['id', 'session_id', 'trade_no', 'ticket_id', 'uuid'])
}

export function failureText(response: { ok: boolean; status: number; body?: { message?: string; status?: string }; error?: string }): string {
  if (!response.ok) return response.body?.message ?? response.error ?? `HTTP ${response.status}`
  if (response.body?.status === 'fail') return response.body.message ?? '请求失败'
  return ''
}

export function parseOAuthProviders(value: unknown): OAuthProvider[] {
  return dataRows(value)
    .map((item) => ({
      driver: field(item, ['driver', 'name', 'type']),
      label: field(item, ['label', 'name', 'driver', 'type']),
    }))
    .filter((item) => item.driver)
}
