import { numericValue } from '../format'
import type { InviteItem, OAuthProvider } from '../store'

export type Row = Record<string, unknown>

export function enabled(value: unknown): boolean {
  if (value === true || value === 1 || value === '1' || value === 'true') return true
  if (value === false || value === 0 || value === '0' || value === 'false') return false
  throw new Error(`boolean flag has invalid value: ${String(value)}`)
}

export function dataRows(value: unknown): Row[] {
  if (Array.isArray(value)) return value as Row[]
  throw new Error('Xboard response data must be an array')
}

export function field(row: Row, key: string): string {
  const value = row[key]
  if (typeof value !== 'string' && typeof value !== 'number') throw new Error(`Xboard row field ${key} is required`)
  return String(value)
}

export function failureText(response: { ok: boolean; status: number; body?: unknown; error?: string }): string {
  const body = response.body as { message?: string; status?: string } | undefined
  if (!response.ok) {
    if (body?.message) return body.message
    if (response.error) return response.error
    throw new Error('Xboard failed response missing message or error')
  }
  if (body?.status === 'fail') {
    if (!body.message) throw new Error('Xboard response status=fail missing message')
    return body.message
  }
  return ''
}

export function parseInviteRows(value: unknown): InviteItem[] {
  if (!value || typeof value !== 'object') throw new Error('invite_fetch response missing data')
  const data = value as Row
  if (!Array.isArray(data.codes)) throw new Error('invite_fetch response missing codes array')
  return (data.codes as Row[]).map((item) => ({
    code: field(item, 'code'),
    status: Math.round(numericValue(item.status)),
  }))
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

export function parseUserCurrencyConfig(value: unknown): { currencySymbol: string; currencyUnit: string } {
  if (!value || typeof value !== 'object') throw new Error('user_config response missing data')
  const data = value as Row
  if (typeof data.currency_symbol !== 'string') throw new Error('user_config currency_symbol is required')
  const unit = data.currency ?? data.currency_unit
  if (typeof unit !== 'string') throw new Error('user_config currency is required')
  return { currencySymbol: data.currency_symbol, currencyUnit: unit }
}
