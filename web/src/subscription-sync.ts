import { subscriptionFetch, xboardRequest } from './api/xboard'
import { formatTrafficBytes, formatUnixDate, numericValue } from './format'
import { translate, type TranslationKey } from './i18n'
import { mergeNodeLists, mergeXboardNodeTags, rawNodeRows, toAppNode, type RawNode } from './nodes'
import { useAppStore, type AppNode, type NoticeItem, type SubscriptionState } from './store'

interface XboardBody {
  data?: unknown
  message?: string
}

interface NoticeFetchBody {
  data?: Array<{ id?: number; title?: string; subject?: string; content?: string; message?: string; created_at?: number }>
}

function t(key: TranslationKey, language: string): string {
  return translate(key, language)
}

function responseError(response: { status: number; error?: string; body?: XboardBody }): string {
  return response.body?.message || response.error || `HTTP ${response.status}`
}

function parseNotices(body: NoticeFetchBody | undefined): NoticeItem[] {
  return (body?.data ?? [])
    .map((row) => ({
      id: Number(row.id ?? 0),
      title: row.title ?? row.subject ?? '',
      content: row.content ?? row.message ?? '',
      createdAt: Number(row.created_at ?? 0),
    }))
    .filter((item) => item.title.trim() || item.content.trim())
}

function subscriptionState(data: Record<string, unknown>, language: string): SubscriptionState {
  const used = numericValue(data.u) + numericValue(data.d)
  const total = numericValue(data.transfer_enable)
  const plan = data.plan && typeof data.plan === 'object' ? (data.plan as Record<string, unknown>) : null
  const planName = String(plan?.name ?? '')
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
  if (!sub.ok) return responseError(sub)

  const data = sub.body?.data && typeof sub.body.data === 'object' ? sub.body.data as Record<string, unknown> : {}
  const url = String(data.subscribe_url ?? data.subscribeUrl ?? '')
  let list: AppNode[] = []
  const xbclientNodes = await xboardRequest<XboardBody>('xbclient_nodes', { baseUrl: state.baseUrl, authData: state.authData })
  if (xbclientNodes.ok) {
    list = rawNodeRows(xbclientNodes.body?.data).map(toAppNode)
  } else if (url) {
    const subscription = await subscriptionFetch(url, 'meta')
    list = (subscription.nodes ?? []).map((node) => toAppNode(node as RawNode))
    const tagRows = await xboardRequest<XboardBody>('nodes', { baseUrl: state.baseUrl, authData: state.authData })
    if (tagRows.ok) list = mergeXboardNodeTags(list, rawNodeRows(tagRows.body?.data))
  }

  state.setSubscribe({ subscribeUrl: url, nodes: mergeNodeLists(state.nodes, list) })
  state.setSubscriptionState(subscriptionState(data, language))

  const noticeResponse = await xboardRequest<NoticeFetchBody>('notices', { baseUrl: state.baseUrl, authData: state.authData })
  if (noticeResponse.ok) state.setNotices(parseNotices(noticeResponse.body))
  return null
}
