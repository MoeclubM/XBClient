import { useState, type FormEvent } from 'react'
import { xboardRequest } from '../../api/xboard'
import { openInAppBrowser } from '../../api/system'
import { formatTrafficBytes, numericValue } from '../../format'
import { dataRows, failureText, field, rowId, shortJson, textData, timeText, type Row, type XboardBody } from './helpers'

interface Props {
  baseUrl: string
  authData: string
  setMessage(value: string): void
}

export function LogsKnowledge({ baseUrl, authData, setMessage }: Props) {
  const [trafficLogs, setTrafficLogs] = useState<Row[]>([])
  const [telegramInfo, setTelegramInfo] = useState<unknown>(null)
  const [knowledgeKeyword, setKnowledgeKeyword] = useState('')
  const [knowledgeRows, setKnowledgeRows] = useState<Row[]>([])

  async function loadTrafficLogs() {
    setMessage('')
    const response = await xboardRequest<XboardBody<unknown>>('traffic_logs', { baseUrl, authData })
    const error = failureText(response)
    if (error) {
      setMessage(`流量日志加载失败：${error}`)
      return
    }
    setTrafficLogs(dataRows(response.body?.data))
  }

  async function loadTelegram() {
    setMessage('')
    const response = await xboardRequest<XboardBody<unknown>>('telegram_bot', { baseUrl, authData })
    const error = failureText(response)
    if (error) {
      setMessage(`Telegram 信息加载失败：${error}`)
      return
    }
    setTelegramInfo(response.body?.data ?? response.body)
    const url = textData(response.body?.data)
    if (url) await openInAppBrowser(url, 'Telegram')
  }

  async function searchKnowledge(event: FormEvent) {
    event.preventDefault()
    setMessage('')
    const response = await xboardRequest<XboardBody<unknown>>('knowledge', { baseUrl, authData, params: { keyword: knowledgeKeyword.trim(), language: navigator.language || 'zh-CN' } })
    const error = failureText(response)
    if (error) {
      setMessage(`知识库搜索失败：${error}`)
      return
    }
    setKnowledgeRows(dataRows(response.body?.data))
  }

  async function openKnowledge(row: Row) {
    const link = field(row, ['url', 'link'])
    if (link) {
      await openInAppBrowser(link, field(row, ['title', 'subject']) || '知识库')
      return
    }
    const id = rowId(row)
    if (!id) {
      setMessage('知识库记录缺少链接或 ID。')
      return
    }
    const response = await xboardRequest<XboardBody<unknown>>('knowledge', { baseUrl, authData, params: { id, language: navigator.language || 'zh-CN' } })
    const error = failureText(response)
    setMessage(error ? `知识库详情加载失败：${error}` : shortJson(response.body?.data ?? response.body))
  }

  return (
    <section className="grid gap-4 lg:grid-cols-3">
      <section className="space-y-3 rounded-2xl bg-surface-low p-5 shadow-sm border border-outline-variant/40">
        <h2 className="text-sm font-bold tracking-tight text-primary">流量日志</h2>
        <button className="rounded-xl bg-primary/10 px-4 py-2 text-xs font-bold text-primary border border-primary/20" onClick={() => void loadTrafficLogs()}>加载流量日志</button>
        <ul className="space-y-2 max-h-72 overflow-y-auto">
          {trafficLogs.map((log, index) => {
            const used = numericValue(log.u) + numericValue(log.d) + numericValue(log.upload) + numericValue(log.download)
            return (
              <li key={rowId(log) || index} className="rounded-xl bg-surface p-3 border border-outline-variant/25 text-xs">
                <p className="font-bold text-on-background">{used > 0 ? formatTrafficBytes(used) : field(log, ['rate', 'type']) || `记录 ${index + 1}`}</p>
                <p className="mt-1 text-on-surface-variant">{timeText(log)}</p>
              </li>
            )
          })}
        </ul>
      </section>

      <section className="space-y-3 rounded-2xl bg-surface-low p-5 shadow-sm border border-outline-variant/40">
        <h2 className="text-sm font-bold tracking-tight text-primary">Telegram</h2>
        <button className="rounded-xl bg-primary/10 px-4 py-2 text-xs font-bold text-primary border border-primary/20" onClick={() => void loadTelegram()}>加载并打开 Bot</button>
        <pre className="max-h-72 overflow-auto whitespace-pre-wrap rounded-xl bg-surface p-3 text-xs text-on-surface-variant border border-outline-variant/25">{telegramInfo ? shortJson(telegramInfo) : '未加载。'}</pre>
      </section>

      <section className="space-y-3 rounded-2xl bg-surface-low p-5 shadow-sm border border-outline-variant/40">
        <h2 className="text-sm font-bold tracking-tight text-primary">知识库</h2>
        <form className="flex gap-2" onSubmit={searchKnowledge}>
          <input className="min-w-0 flex-1 rounded-xl bg-surface px-3 py-2 text-sm outline-none border border-outline-variant/50" placeholder="关键词" value={knowledgeKeyword} onChange={(event) => setKnowledgeKeyword(event.target.value)} />
          <button className="rounded-xl bg-primary px-3 py-2 text-xs font-bold text-white" type="submit">搜索</button>
        </form>
        <ul className="space-y-2 max-h-72 overflow-y-auto">
          {knowledgeRows.map((item, index) => (
            <li key={rowId(item) || index} className="rounded-xl bg-surface p-3 border border-outline-variant/25 text-xs">
              <button className="w-full text-left" onClick={() => void openKnowledge(item)}>
                <p className="font-bold text-on-background">{field(item, ['title', 'subject']) || `文章 ${index + 1}`}</p>
                <p className="mt-1 line-clamp-2 text-on-surface-variant">{field(item, ['content', 'summary', 'description'])}</p>
              </button>
            </li>
          ))}
        </ul>
      </section>
    </section>
  )
}
