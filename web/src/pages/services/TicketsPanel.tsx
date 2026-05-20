import { useState, type FormEvent } from 'react'
import { xboardRequest } from '../../api/xboard'
import { dataRows, failureText, field, rowId, shortJson, timeText, type Row, type XboardBody } from './helpers'

interface Props {
  baseUrl: string
  authData: string
  tickets: Row[]
  setTickets(value: Row[]): void
  setMessage(value: string): void
}

export function TicketsPanel({ baseUrl, authData, tickets, setTickets, setMessage }: Props) {
  const [ticketSubject, setTicketSubject] = useState('')
  const [ticketMessage, setTicketMessage] = useState('')
  const [selectedTicketId, setSelectedTicketId] = useState('')
  const [ticketDetail, setTicketDetail] = useState<unknown>(null)
  const [ticketReply, setTicketReply] = useState('')
  const detailRows = dataRows(ticketDetail)

  async function createTicket(event: FormEvent) {
    event.preventDefault()
    setMessage('')
    const response = await xboardRequest<XboardBody>('ticket_save', { baseUrl, authData, params: { subject: ticketSubject.trim(), message: ticketMessage.trim(), level: 0 } })
    const error = failureText(response)
    if (error) {
      setMessage(`创建工单失败：${error}`)
      return
    }
    setTicketSubject('')
    setTicketMessage('')
    const refreshed = await xboardRequest<XboardBody<unknown>>('tickets', { baseUrl, authData })
    const refreshError = failureText(refreshed)
    if (refreshError) {
      setMessage(`工单已创建，但刷新列表失败：${refreshError}`)
      return
    }
    setTickets(dataRows(refreshed.body?.data))
    setMessage(response.body?.message ?? '工单已创建。')
  }

  async function openTicket(ticket: Row) {
    const id = rowId(ticket)
    setSelectedTicketId(id)
    setTicketDetail(null)
    const response = await xboardRequest<XboardBody<unknown>>('tickets', { baseUrl, authData, params: { id } })
    const error = failureText(response)
    if (error) {
      setMessage(`工单详情加载失败：${error}`)
      return
    }
    setTicketDetail(response.body?.data ?? response.body)
  }

  async function replyTicket(event: FormEvent) {
    event.preventDefault()
    setMessage('')
    const response = await xboardRequest<XboardBody>('ticket_reply', { baseUrl, authData, params: { id: selectedTicketId, message: ticketReply.trim() } })
    const error = failureText(response)
    if (error) {
      setMessage(`回复工单失败：${error}`)
      return
    }
    setTicketReply('')
    await openTicket({ id: selectedTicketId })
    setMessage(response.body?.message ?? '工单已回复。')
  }

  async function closeTicket() {
    setMessage('')
    const response = await xboardRequest<XboardBody>('ticket_close', { baseUrl, authData, params: { id: selectedTicketId } })
    const error = failureText(response)
    if (error) {
      setMessage(`关闭工单失败：${error}`)
      return
    }
    const refreshed = await xboardRequest<XboardBody<unknown>>('tickets', { baseUrl, authData })
    const refreshError = failureText(refreshed)
    if (refreshError) {
      setMessage(`工单已关闭，但刷新列表失败：${refreshError}`)
      return
    }
    setTickets(dataRows(refreshed.body?.data))
    setMessage(response.body?.message ?? '工单已关闭。')
  }

  return (
    <section className="grid gap-4 lg:grid-cols-2">
      <section className="space-y-3 rounded-2xl bg-surface-low p-5 border border-outline-variant/40">
        <h2 className="text-sm font-bold tracking-tight text-primary">工单</h2>
        <form className="space-y-2" onSubmit={createTicket}>
          <input className="w-full rounded-xl bg-surface px-3 py-2 text-sm outline-none border border-outline-variant/50" placeholder="标题" value={ticketSubject} onChange={(event) => setTicketSubject(event.target.value)} required />
          <textarea className="w-full rounded-xl bg-surface px-3 py-2 text-sm outline-none border border-outline-variant/50" placeholder="问题描述" rows={3} value={ticketMessage} onChange={(event) => setTicketMessage(event.target.value)} required />
          <button className="rounded-xl bg-primary px-4 py-2 text-xs font-bold text-white" type="submit">创建工单</button>
        </form>
        <ul className="space-y-2 max-h-80 overflow-y-auto">
          {tickets.map((ticket, index) => (
            <li key={rowId(ticket) || index} className="rounded-xl bg-surface p-3 border border-outline-variant/25 text-xs">
              <div className="flex items-start justify-between gap-3">
                <div className="min-w-0">
                  <p className="truncate font-bold text-on-background">{field(ticket, ['subject', 'title']) || `工单 ${rowId(ticket)}`}</p>
                  <p className="mt-1 text-on-surface-variant">{field(ticket, ['status', 'level'])} {timeText(ticket)}</p>
                </div>
                <button className="shrink-0 rounded-lg bg-primary/10 px-3 py-1.5 font-bold text-primary" onClick={() => void openTicket(ticket)}>打开</button>
              </div>
            </li>
          ))}
          {tickets.length === 0 && <li className="text-xs text-on-surface-variant">暂无工单。</li>}
        </ul>
      </section>

      <section className="space-y-3 rounded-2xl bg-surface-low p-5 border border-outline-variant/40">
        <div className="flex items-center justify-between">
          <h2 className="text-sm font-bold tracking-tight text-primary">工单详情</h2>
          {selectedTicketId && <button className="rounded-lg bg-rose-500/10 px-3 py-1.5 text-xs font-bold text-rose-500" onClick={() => void closeTicket()}>关闭工单</button>}
        </div>
        {detailRows.length > 0 ? (
          <ul className="space-y-2 max-h-72 overflow-y-auto">
            {detailRows.map((item, index) => (
              <li key={rowId(item) || index} className="rounded-xl bg-surface p-3 border border-outline-variant/25 text-xs">
                <p className="font-bold text-on-background">{field(item, ['user_name', 'email', 'role']) || `#${index + 1}`}</p>
                <p className="mt-1 whitespace-pre-wrap text-on-surface-variant">{field(item, ['message', 'content', 'reply']) || shortJson(item)}</p>
              </li>
            ))}
          </ul>
        ) : (
          <pre className="max-h-72 overflow-auto whitespace-pre-wrap rounded-xl bg-surface p-3 text-xs text-on-surface-variant border border-outline-variant/25">{ticketDetail ? shortJson(ticketDetail) : '打开工单后显示详情。'}</pre>
        )}
        {selectedTicketId && (
          <form className="space-y-2" onSubmit={replyTicket}>
            <textarea className="w-full rounded-xl bg-surface px-3 py-2 text-sm outline-none border border-outline-variant/50" placeholder="回复内容" rows={3} value={ticketReply} onChange={(event) => setTicketReply(event.target.value)} required />
            <button className="rounded-xl bg-primary px-4 py-2 text-xs font-bold text-white" type="submit">回复工单</button>
          </form>
        )}
      </section>
    </section>
  )
}
