import { xboardRequest } from '../../api/xboard'
import { openInAppBrowser } from '../../api/system'
import type { OAuthProvider } from '../../store'
import { dataRows, failureText, field, rowId, shortJson, textData, timeText, type Row, type XboardBody } from './helpers'

interface Props {
  baseUrl: string
  authData: string
  oauthProviders: OAuthProvider[]
  bindings: Row[]
  setBindings(value: Row[]): void
  giftCode: string
  setGiftCode(value: string): void
  giftHistory: Row[]
  setGiftHistory(value: Row[]): void
  setMessage(value: string): void
}

export function OAuthGift({ baseUrl, authData, oauthProviders, bindings, setBindings, giftCode, setGiftCode, giftHistory, setGiftHistory, setMessage }: Props) {
  async function bindOAuth(provider: OAuthProvider) {
    setMessage('')
    const response = await xboardRequest<XboardBody<unknown>>('oauth_bind_prepare', { baseUrl, authData, params: { driver: provider.driver } })
    const error = failureText(response)
    if (error) {
      setMessage(`OAuth 绑定失败：${error}`)
      return
    }
    const url = textData(response.body?.data)
    if (!url) {
      setMessage(response.body?.message ?? 'OAuth 绑定响应缺少 URL。')
      return
    }
    await openInAppBrowser(url, `${provider.label || provider.driver} 绑定`)
    setMessage('已在应用内打开 OAuth 绑定页面，完成后请刷新绑定状态。')
  }

  async function unbindOAuth(driver: string) {
    setMessage('')
    const response = await xboardRequest<XboardBody>('oauth_unbind', { baseUrl, authData, params: { driver } })
    const error = failureText(response)
    if (error) {
      setMessage(`OAuth 解绑失败：${error}`)
      return
    }
    const refreshed = await xboardRequest<XboardBody<unknown>>('oauth_bindings', { baseUrl, authData })
    const refreshError = failureText(refreshed)
    if (refreshError) {
      setMessage(`OAuth 已解绑，但刷新绑定状态失败：${refreshError}`)
      return
    }
    setBindings(dataRows(refreshed.body?.data))
    setMessage(response.body?.message ?? 'OAuth 已解绑。')
  }

  async function checkGiftCard() {
    setMessage('')
    const response = await xboardRequest<XboardBody<unknown>>('gift_card_check', { baseUrl, authData, params: { code: giftCode.trim() } })
    const error = failureText(response)
    setMessage(error ? `礼品卡查询失败：${error}` : shortJson(response.body?.data) || response.body?.message || '礼品卡可用。')
  }

  async function redeemGiftCard() {
    setMessage('')
    const response = await xboardRequest<XboardBody>('gift_card_redeem', { baseUrl, authData, params: { code: giftCode.trim() } })
    const error = failureText(response)
    if (error) {
      setMessage(`礼品卡兑换失败：${error}`)
      return
    }
    const history = await xboardRequest<XboardBody<unknown>>('gift_card_history', { baseUrl, authData, params: { page: 1, per_page: 10 } })
    const historyError = failureText(history)
    if (historyError) {
      setMessage(`礼品卡已兑换，但刷新历史失败：${historyError}`)
      return
    }
    setGiftHistory(dataRows(history.body?.data))
    setMessage(response.body?.message ?? '礼品卡已兑换。')
  }

  return (
    <section className="grid gap-4 md:grid-cols-2">
      <section className="space-y-3 rounded-2xl bg-surface-low p-5 shadow-sm border border-outline-variant/40">
        <h2 className="text-sm font-bold tracking-tight text-primary">OAuth 绑定</h2>
        <div className="flex flex-wrap gap-2">
          {oauthProviders.map((provider) => {
            const binding = bindings.find((item) => field(item, ['driver', 'provider', 'type']) === provider.driver)
            return (
              <div key={provider.driver} className="rounded-xl bg-surface p-3 border border-outline-variant/25 text-xs min-w-40 flex-1">
                <p className="font-bold text-on-background">{provider.label || provider.driver}</p>
                <p className="mt-1 truncate text-on-surface-variant">{binding ? field(binding, ['email', 'name', 'openid', 'identifier']) || '已绑定' : '未绑定'}</p>
                <button className="mt-3 rounded-lg bg-primary/10 px-3 py-1.5 font-bold text-primary border border-primary/20" onClick={() => binding ? void unbindOAuth(provider.driver) : void bindOAuth(provider)}>
                  {binding ? '解绑' : '绑定'}
                </button>
              </div>
            )
          })}
          {oauthProviders.length === 0 && <p className="text-xs text-on-surface-variant">站点未返回 OAuth 提供商。</p>}
        </div>
      </section>

      <section className="space-y-3 rounded-2xl bg-surface-low p-5 shadow-sm border border-outline-variant/40">
        <h2 className="text-sm font-bold tracking-tight text-primary">礼品卡</h2>
        <div className="flex gap-2">
          <input className="min-w-0 flex-1 rounded-xl bg-surface px-3 py-2 text-sm outline-none border border-outline-variant/50" placeholder="礼品卡代码" value={giftCode} onChange={(event) => setGiftCode(event.target.value)} />
          <button className="rounded-xl bg-primary/10 px-3 py-2 text-xs font-bold text-primary" onClick={() => void checkGiftCard()}>查询</button>
          <button className="rounded-xl bg-primary px-3 py-2 text-xs font-bold text-white" onClick={() => void redeemGiftCard()}>兑换</button>
        </div>
        <ul className="space-y-2 max-h-48 overflow-y-auto">
          {giftHistory.map((item, index) => (
            <li key={rowId(item) || index} className="rounded-xl bg-surface p-3 border border-outline-variant/25 text-xs">
              <p className="font-bold text-on-background">{field(item, ['code', 'name', 'type']) || `记录 ${index + 1}`}</p>
              <p className="mt-1 text-on-surface-variant">{field(item, ['status', 'description', 'remark'])} {timeText(item)}</p>
            </li>
          ))}
          {giftHistory.length === 0 && <li className="text-xs text-on-surface-variant">暂无礼品卡记录。</li>}
        </ul>
      </section>
    </section>
  )
}
