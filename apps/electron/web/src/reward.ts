import { formatTrafficBytes, numericValue } from './format'
import { translate, type TranslationKey } from './i18n'
import type { AdRewardLogItem } from './store'

export function enabled(value: unknown): boolean {
  if (value === true || value === 1 || value === '1' || value === 'true') return true
  if (value === false || value === 0 || value === '0' || value === 'false') return false
  throw new Error(`boolean flag has invalid value: ${String(value)}`)
}

export function parseRewardLogs(value: unknown, appLanguage = 'zh-CN'): AdRewardLogItem[] {
  return rewardRows(value).map((row) => ({
    id: Math.round(numericValue(row.id)),
    scene: requiredText(row, 'scene'),
    transactionId: requiredText(row, 'transaction_id'),
    status: requiredText(row, 'status'),
    error: requiredText(row, 'error'),
    rewardContent: rewardContentText(row, appLanguage),
    usedAt: Math.round(numericValue(row.used_at)),
    createdAt: Math.round(numericValue(row.created_at)),
  }))
}

export function rewardStatusText(status: string, appLanguage = 'zh-CN'): string {
  if (status === 'credited') return translate('reward_status_credited', appLanguage)
  if (status === 'failed') return translate('reward_status_failed', appLanguage)
  if (status === 'pending') return translate('reward_status_pending', appLanguage)
  throw new Error(`reward status is invalid: ${status}`)
}

function rewardRows(value: unknown): Array<Record<string, unknown>> {
  if (Array.isArray(value)) return value as Array<Record<string, unknown>>
  throw new Error('reward history response data must be an array')
}

function rewardContentText(item: Record<string, unknown>, appLanguage: string): string {
  if (item.reward_content !== undefined && item.reward_content !== null) {
    if (typeof item.reward_content !== 'string') throw new Error('reward log reward_content must be a string')
    const text = item.reward_content.trim()
    if (text) return text
  }
  const rewards = item.rewards as Record<string, unknown> | undefined
  if (!rewards || typeof rewards !== 'object') throw new Error('reward log missing reward_content or rewards')
  const parts: string[] = []
  if (rewards.balance !== undefined && rewards.balance !== null) {
    const balance = numericValue(rewards.balance)
    if (balance > 0) parts.push(`${label('reward_balance', appLanguage)} ${trimNumber(balance / 100)}`)
  }
  if (rewards.transfer_enable !== undefined && rewards.transfer_enable !== null) {
    const transfer = numericValue(rewards.transfer_enable)
    if (transfer > 0) parts.push(`${label('reward_transfer', appLanguage)} ${formatTrafficBytes(transfer)}`)
  }
  if (rewards.device_limit !== undefined && rewards.device_limit !== null) {
    const deviceLimit = Math.round(numericValue(rewards.device_limit))
    if (deviceLimit > 0) parts.push(`${label('reward_device_limit', appLanguage)} +${deviceLimit}`)
  }
  if (rewards.reset_package !== undefined && rewards.reset_package !== null) {
    const resetPackage = rewards.reset_package
    if (typeof resetPackage === 'boolean' ? resetPackage : numericValue(resetPackage) > 0) parts.push(label('reward_reset_package', appLanguage))
  }
  if (rewards.plan_id !== undefined && rewards.plan_id !== null) {
    const planId = Math.round(numericValue(rewards.plan_id))
    if (planId > 0) parts.push(`${label('reward_plan', appLanguage)} #${planId}`)
  }
  if (rewards.plan_validity_days !== undefined && rewards.plan_validity_days !== null) {
    const planValidityDays = Math.round(numericValue(rewards.plan_validity_days))
    if (planValidityDays > 0) parts.push(`${label('reward_plan_validity', appLanguage)} ${planValidityDays} ${label('days_suffix', appLanguage)}`)
  }
  if (rewards.expire_days !== undefined && rewards.expire_days !== null) {
    const expireDays = Math.round(numericValue(rewards.expire_days))
    if (expireDays > 0) parts.push(`${label('reward_expire', appLanguage)} +${expireDays} ${label('days_suffix', appLanguage)}`)
  }
  if (!parts.length) throw new Error('reward log rewards is empty')
  return parts.join(' · ')
}

function requiredText(item: Record<string, unknown>, key: string): string {
  const value = item[key]
  if (typeof value !== 'string') throw new Error(`reward log missing ${key}`)
  return value
}

function label(key: TranslationKey, appLanguage: string): string {
  return translate(key, appLanguage)
}

function trimNumber(value: number): string {
  return value.toFixed(2).replace(/\.?0+$/, '')
}
