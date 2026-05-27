import { formatTrafficBytes, numericValue } from './format'
import { translate, type TranslationKey } from './i18n'
import type { AdRewardLogItem } from './store'

export function enabled(value: unknown): boolean {
  return value === true || value === 1 || value === '1' || value === 'true'
}

export function parseRewardLogs(value: unknown, appLanguage = 'zh-CN'): AdRewardLogItem[] {
  return rewardRows(value).map((row) => ({
    id: Math.round(numericValue(row.id)),
    scene: String(row.scene ?? ''),
    transactionId: String(row.transaction_id ?? ''),
    status: String(row.status ?? ''),
    error: String(row.error ?? ''),
    rewardContent: rewardContentText(row, appLanguage),
    usedAt: Math.round(numericValue(row.used_at)),
    createdAt: Math.round(numericValue(row.created_at)),
  }))
}

export function rewardStatusText(status: string, appLanguage = 'zh-CN'): string {
  if (status === 'credited') return translate('reward_status_credited', appLanguage)
  if (status === 'failed') return translate('reward_status_failed', appLanguage)
  return translate('reward_status_pending', appLanguage)
}

function rewardRows(value: unknown): Array<Record<string, unknown>> {
  if (Array.isArray(value)) return value as Array<Record<string, unknown>>
  if (value && typeof value === 'object') {
    const object = value as Record<string, unknown>
    for (const key of ['data', 'list', 'items', 'logs']) {
      if (Array.isArray(object[key])) return object[key] as Array<Record<string, unknown>>
      if (object[key] && typeof object[key] === 'object') {
        const nested = object[key] as Record<string, unknown>
        for (const nestedKey of ['data', 'list', 'items', 'logs']) {
          if (Array.isArray(nested[nestedKey])) return nested[nestedKey] as Array<Record<string, unknown>>
        }
      }
    }
  }
  return []
}

function rewardContentText(item: Record<string, unknown>, appLanguage: string): string {
  for (const key of ['reward_content', 'reward_text', 'reward_description', 'description']) {
    const text = String(item[key] ?? '').trim()
    if (text) return text
  }
  const rewards = (item.rewards ?? item.rewards_given) as Record<string, unknown> | undefined
  if (!rewards || typeof rewards !== 'object') return ''
  const parts: string[] = []
  const balance = numericValue(rewards.balance)
  if (balance > 0) parts.push(`${label('reward_balance', appLanguage)} ${trimNumber(balance / 100)}`)
  const transfer = numericValue(rewards.transfer_enable)
  if (transfer > 0) parts.push(`${label('reward_transfer', appLanguage)} ${formatTrafficBytes(transfer)}`)
  const deviceLimit = Math.round(numericValue(rewards.device_limit))
  if (deviceLimit > 0) parts.push(`${label('reward_device_limit', appLanguage)} +${deviceLimit}`)
  if (enabled(rewards.reset_package) || numericValue(rewards.reset_package) > 0) parts.push(label('reward_reset_package', appLanguage))
  const planId = Math.round(numericValue(rewards.plan_id))
  if (planId > 0) parts.push(`${label('reward_plan', appLanguage)} #${planId}`)
  const planValidityDays = Math.round(numericValue(rewards.plan_validity_days))
  if (planValidityDays > 0) parts.push(`${label('reward_plan_validity', appLanguage)} ${planValidityDays} ${label('days_suffix', appLanguage)}`)
  const expireDays = Math.round(numericValue(rewards.expire_days))
  if (expireDays > 0) parts.push(`${label('reward_expire', appLanguage)} +${expireDays} ${label('days_suffix', appLanguage)}`)
  return parts.join(' · ')
}

function label(key: TranslationKey, appLanguage: string): string {
  return translate(key, appLanguage)
}

function trimNumber(value: number): string {
  return value.toFixed(2).replace(/\.?0+$/, '')
}
