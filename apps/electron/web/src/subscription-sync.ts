import { failureText } from './api/helpers'
import { subscriptionFetch, xboardRequest, type XboardBody } from './api/xboard'
import { formatTrafficBytes, formatUnixDate, numericValue } from './format'
import { translate, type TranslationKey } from './i18n'
import { rawNodeRows, toAppNode } from './nodes'
import { useAppStore, type AppNode, type NoticeItem, type SubscriptionState } from './store'
import { saveSubscriptionCache } from './store/persist'

interface NoticeFetchBody {
  data?: Array<{ id?: unknown; title?: unknown; content?: unknown; created_at?: unknown }>
}

function t(key: TranslationKey, language: string): string {
  return translate(key, language)
}

function parseNotices(body: NoticeFetchBody | undefined): NoticeItem[] {
  if (!Array.isArray(body?.data)) throw new Error('公告响应 data 必须是数组。')
  return body.data
    .map((row) => {
      if (typeof row.title !== 'string' || typeof row.content !== 'string') throw new Error('公告缺少 title 或 content。')
      const id = Number(row.id)
      const createdAt = Number(row.created_at)
      if (!Number.isFinite(id) || !Number.isFinite(createdAt)) throw new Error('公告 id 或 created_at 无效。')
      return {
        id,
        title: row.title,
        content: row.content,
        createdAt,
      }
    })
}

function subscriptionState(data: Record<string, unknown>, language: string): SubscriptionState {
  for (const key of ['u', 'd', 'transfer_enable', 'expired_at', 'plan_id']) {
    if (data[key] === undefined || data[key] === null) throw new Error(`订阅同步响应缺少 ${key}。`)
  }
  const used = numericValue(data.u) + numericValue(data.d)
  const total = numericValue(data.transfer_enable)
  const plan = data.plan && typeof data.plan === 'object' ? (data.plan as Record<string, unknown>) : null
  if (plan && typeof plan.name !== 'string') throw new Error('订阅套餐缺少 name。')
  const planName = plan ? (plan.name as string) : ''
  const expiredAt = numericValue(data.expired_at)
  const planId = numericValue(data.plan_id)
  return {
    summary: [
      planName,
      total > 0 ? `${t('used_traffic', language)} ${formatTrafficBytes(used)} / ${formatTrafficBytes(total)}` : '',
      expiredAt > 0 ? `${t('expires_prefix', language)} ${formatUnixDate(expiredAt)}` : '',
    ].filter(Boolean).join(' · '),
    blockReason: (planId <= 0 && !plan
      ? 'no_plan'
      : expiredAt > 0 && expiredAt <= Date.now() / 1000
        ? 'expired'
        : total <= 0 || used >= total
          ? 'traffic_exceeded'
          : '') as SubscriptionState['blockReason'],
    trafficUsedBytes: used,
    trafficTotalBytes: total,
    planName,
    expiredAt,
  }
}

export async function syncSubscription(): Promise<string | null> {
  const state = useAppStore.getState()
  const language = state.settings.appLanguage
  const sub = await xboardRequest<XboardBody>('user_subscribe', { baseUrl: state.baseUrl, authData: state.authData })
  if (!sub.ok) return failureText(sub)

  if (!sub.body?.data || typeof sub.body.data !== 'object') throw new Error('订阅同步响应缺少 data。')
  const data = sub.body.data as Record<string, unknown>
  const url = typeof data.subscribe_url === 'string' ? data.subscribe_url : ''
  let list: AppNode[] = []
  let metaSubscription: Awaited<ReturnType<typeof subscriptionFetch>> | null = null
  if (url) {
    metaSubscription = await subscriptionFetch(url, 'meta')
    if (!metaSubscription.ok) {
      if (!metaSubscription.error) throw new Error('订阅规则同步失败但缺少 error 字段。')
      return metaSubscription.error
    }
    const routing = metaSubscription.routing
    if (!routing) throw new Error('订阅规则响应缺少 routing。')
    state.setRouting({
      hasRules: Boolean(routing.has_rules),
      ruleCount: Number(routing.rule_count),
      proxyGroupCount: Number(routing.proxy_group_count),
      ruleProviderCount: Number(routing.rule_provider_count),
      rulesPreview: routing.rules_preview,
      routeConfigYaml: typeof routing.route_config_yaml === 'string' ? routing.route_config_yaml : null,
    })
  }
  const xbclientNodes = await xboardRequest<XboardBody>('xbclient_nodes', { baseUrl: state.baseUrl, authData: state.authData })
  if (!xbclientNodes.ok) return failureText(xbclientNodes)
  list = rawNodeRows(xbclientNodes.body?.data).map(toAppNode)

  const nextSubscription = subscriptionState(data, language)
  state.setSubscribe({ subscribeUrl: url, nodes: list })
  state.setSubscriptionState(nextSubscription)
  await saveSubscriptionCache({
    authData: state.authData,
    subscribeUrl: url,
    nodes: list,
    subscription: nextSubscription,
    routing: useAppStore.getState().routing,
  })

  const noticeResponse = await xboardRequest<NoticeFetchBody>('notices', { baseUrl: state.baseUrl, authData: state.authData })
  if (!noticeResponse.ok) return failureText(noticeResponse)
  state.setNotices(parseNotices(noticeResponse.body))
  return null
}
