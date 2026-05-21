import { useEffect, useState } from 'react'
import { xboardRequest } from '../api/xboard'
import { useTranslation } from '../i18n'
import { useAppStore } from '../store'
import { AccountSecurity } from './services/AccountSecurity'
import { LogsKnowledge } from './services/LogsKnowledge'
import { OAuthGift } from './services/OAuthGift'
import { TicketsPanel } from './services/TicketsPanel'
import { dataRows, failureText, parseOAuthProviders, type Row, type XboardBody } from './services/helpers'

export function Services() {
  const t = useTranslation()
  const baseUrl = useAppStore((s) => s.baseUrl)
  const authData = useAppStore((s) => s.authData)
  const appName = useAppStore((s) => s.buildConfig?.app_name ?? '')
  const oauthProviders = useAppStore((s) => s.oauthProviders)
  const setAuthConfig = useAppStore((s) => s.setAuthConfig)

  const [message, setMessage] = useState('')
  const [loading, setLoading] = useState(true)
  const [sessions, setSessions] = useState<Row[]>([])
  const [bindings, setBindings] = useState<Row[]>([])
  const [giftCode, setGiftCode] = useState('')
  const [giftHistory, setGiftHistory] = useState<Row[]>([])
  const [tickets, setTickets] = useState<Row[]>([])

  useEffect(() => {
    let active = true

    async function load() {
      setLoading(true)
      setMessage('')
      try {
        const requests = [
          xboardRequest<XboardBody<unknown>>('active_sessions', { baseUrl, authData }),
          xboardRequest<XboardBody<unknown>>('oauth_bindings', { baseUrl, authData }),
          xboardRequest<XboardBody<unknown>>('gift_card_history', { baseUrl, authData, params: { page: 1, per_page: 10 } }),
          xboardRequest<XboardBody<unknown>>('tickets', { baseUrl, authData }),
        ]

        const guestConfigIndex = requests.length
        if (oauthProviders.length === 0) {
          requests.push(xboardRequest<XboardBody<{ oauth_providers?: unknown }>>('guest_config', { baseUrl }))
        }

        const responses = await Promise.all(requests)
        if (!active) return

        const firstError = responses.map(failureText).find(Boolean)
        if (firstError) setMessage(`服务数据加载不完整：${firstError}`)

        setSessions(dataRows(responses[0].body?.data))
        setBindings(dataRows(responses[1].body?.data))
        setGiftHistory(dataRows(responses[2].body?.data))
        setTickets(dataRows(responses[3].body?.data))

        if (responses[guestConfigIndex]) {
          const providers = parseOAuthProviders((responses[guestConfigIndex].body as XboardBody<{ oauth_providers?: unknown }> | undefined)?.data?.oauth_providers)
          if (providers.length > 0) setAuthConfig({ oauthProviders: providers })
        }
      } catch (err) {
        if (active) setMessage(err instanceof Error ? err.message : String(err))
      } finally {
        if (active) setLoading(false)
      }
    }

    if (baseUrl && authData) void load()
    return () => {
      active = false
    }
  }, [baseUrl, authData, oauthProviders.length, setAuthConfig])

  return (
    <main className="mx-auto flex w-full max-w-6xl flex-col gap-5 px-4 py-5 pb-24 md:pb-6">
      <section className="rounded-2xl bg-surface-low p-5 text-on-surface border border-outline-variant/40">
        <p className="text-xs font-bold uppercase tracking-[0.28em] opacity-80">{appName}</p>
        <h1 className="mt-2 text-2xl font-black tracking-tight">{t('nav_services')}</h1>
        <p className="mt-2 max-w-3xl text-sm text-on-surface-variant">
          账号安全、OAuth 绑定、礼品卡、工单、流量日志、Telegram 和知识库集中管理。
        </p>
      </section>

      {message && (
        <section className="rounded-2xl bg-surface-low p-4 text-sm text-on-surface-variant border border-outline-variant/40">
          {message}
        </section>
      )}

      <AccountSecurity baseUrl={baseUrl} authData={authData} loading={loading} sessions={sessions} setSessions={setSessions} setMessage={setMessage} />
      <OAuthGift
        baseUrl={baseUrl}
        authData={authData}
        oauthProviders={oauthProviders}
        bindings={bindings}
        setBindings={setBindings}
        giftCode={giftCode}
        setGiftCode={setGiftCode}
        giftHistory={giftHistory}
        setGiftHistory={setGiftHistory}
        setMessage={setMessage}
      />
      <TicketsPanel baseUrl={baseUrl} authData={authData} tickets={tickets} setTickets={setTickets} setMessage={setMessage} />
      <LogsKnowledge baseUrl={baseUrl} authData={authData} setMessage={setMessage} />
    </main>
  )
}
