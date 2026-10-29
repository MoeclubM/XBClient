import { invoke } from '../platform/electron'
import type { RawNode } from '../nodes'

export interface SubscriptionRouting {
  has_rules: boolean
  rule_count: number
  proxy_group_count: number
  rule_provider_count: number
  rules_preview: string[]
  route_config_yaml: string | null
}

export interface SubscriptionResult {
  ok: boolean
  status: number
  format?: string
  flag?: string
  subscription_userinfo?: string | null
  nodes?: RawNode[]
  routing?: SubscriptionRouting
  error?: string
  body?: string
}

export async function subscriptionFetch(
  url: string,
  flag: 'meta' | 'sing-box' = 'meta',
  authorization?: string,
): Promise<SubscriptionResult> {
  return invoke<SubscriptionResult>('subscription_fetch', { url, flag, authorization })
}

