import type { OAuthProvider } from '../store'

export type Row = Record<string, unknown>

export function dataRows(value: unknown): Row[] {
  if (Array.isArray(value)) return value as Row[]
  throw new Error('Xboard response data must be an array')
}

export function field(row: Row, key: string): string {
  const value = row[key]
  if (typeof value !== 'string' && typeof value !== 'number') throw new Error(`Xboard row field ${key} is required`)
  return String(value)
}

export function rowId(row: Row): string {
  return field(row, 'id')
}

export function failureText(response: { ok: boolean; status: number; body?: { message?: string; status?: string }; error?: string }): string {
  if (!response.ok) {
    if (response.body?.message) return response.body.message
    if (response.error) return response.error
    throw new Error('Xboard failed response missing message or error')
  }
  if (response.body?.status === 'fail') {
    if (!response.body.message) throw new Error('Xboard response status=fail missing message')
    return response.body.message
  }
  return ''
}

export function parseOAuthProviders(value: unknown): OAuthProvider[] {
  return dataRows(value)
    .map((item) => {
      const driver = field(item, 'driver')
      const label = field(item, 'label')
      if (!driver || !label) throw new Error('oauth provider missing driver or label')
      return { driver, label }
    })
}
