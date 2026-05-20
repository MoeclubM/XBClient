import { useEffect, useState, type FormEvent } from 'react'
import { xboardRequest } from '../api/xboard'
import { useAppStore } from '../store'
import { dataRows, failureText, field, rowId, timeText, type Row, type XboardBody } from './services/helpers'

export function Tickets() {
  const baseUrl = useAppStore((s) => s.baseUrl)
  const authData = useAppStore((s) => s.authData)
  const appName = useAppStore((s) => s.buildConfig?.app_name ?? '')

  const [message, setMessage] = useState('')
  const [loading, setLoading] = useState(true)
  const [tickets, setTickets] = useState<Row[]>([])
  
  // Create ticket form states
  const [ticketSubject, setTicketSubject] = useState('')
  const [ticketMessage, setTicketMessage] = useState('')
  const [showCreateModal, setShowCreateModal] = useState(false)
  const [submitting, setSubmitting] = useState(false)

  // Selected ticket detail states
  const [selectedTicketId, setSelectedTicketId] = useState('')
  const [ticketDetail, setTicketDetail] = useState<unknown>(null)
  const [ticketReply, setTicketReply] = useState('')
  const [loadingDetail, setLoadingDetail] = useState(false)
  const [replying, setReplying] = useState(false)
  const [closing, setClosing] = useState(false)

  const detailRows = dataRows(ticketDetail)

  // Load ticket list
  async function loadTickets(silent = false) {
    if (!silent) setLoading(true)
    try {
      const response = await xboardRequest<XboardBody<unknown>>('tickets', { baseUrl, authData })
      const error = failureText(response)
      if (error) {
        setMessage(`加载工单失败：${error}`)
      } else {
        setTickets(dataRows(response.body?.data))
      }
    } catch (err) {
      setMessage(err instanceof Error ? err.message : String(err))
    } finally {
      if (!silent) setLoading(false)
    }
  }

  useEffect(() => {
    if (baseUrl && authData) {
      void loadTickets()
    }
  }, [baseUrl, authData])

  async function openTicket(ticketId: string) {
    setSelectedTicketId(ticketId)
    setLoadingDetail(true)
    setMessage('')
    try {
      const response = await xboardRequest<XboardBody<unknown>>('tickets', { baseUrl, authData, params: { id: ticketId } })
      const error = failureText(response)
      if (error) {
        setMessage(`工单详情加载失败：${error}`)
        return
      }
      setTicketDetail(response.body?.data ?? response.body)
    } catch (err) {
      setMessage(err instanceof Error ? err.message : String(err))
    } finally {
      setLoadingDetail(false)
    }
  }

  async function createTicket(event: FormEvent) {
    event.preventDefault()
    if (!ticketSubject.trim() || !ticketMessage.trim()) return
    setSubmitting(true)
    setMessage('')
    try {
      const response = await xboardRequest<XboardBody>('ticket_save', {
        baseUrl,
        authData,
        params: { subject: ticketSubject.trim(), message: ticketMessage.trim(), level: 0 },
      })
      const error = failureText(response)
      if (error) {
        setMessage(`创建工单失败：${error}`)
        return
      }
      setTicketSubject('')
      setTicketMessage('')
      setShowCreateModal(false)
      await loadTickets(true)
      setMessage(response.body?.message ?? '工单已成功创建！')
    } catch (err) {
      setMessage(err instanceof Error ? err.message : String(err))
    } finally {
      setSubmitting(false)
    }
  }

  async function replyTicket(event: FormEvent) {
    event.preventDefault()
    if (!ticketReply.trim() || !selectedTicketId) return
    setReplying(true)
    setMessage('')
    try {
      const response = await xboardRequest<XboardBody>('ticket_reply', {
        baseUrl,
        authData,
        params: { id: selectedTicketId, message: ticketReply.trim() },
      })
      const error = failureText(response)
      if (error) {
        setMessage(`回复工单失败：${error}`)
        return
      }
      setTicketReply('')
      await openTicket(selectedTicketId)
    } catch (err) {
      setMessage(err instanceof Error ? err.message : String(err))
    } finally {
      setReplying(false)
    }
  }

  async function closeTicket() {
    if (!selectedTicketId) return
    if (!window.confirm('您确定要关闭该工单吗？')) return
    setClosing(true)
    setMessage('')
    try {
      const response = await xboardRequest<XboardBody>('ticket_close', {
        baseUrl,
        authData,
        params: { id: selectedTicketId },
      })
      const error = failureText(response)
      if (error) {
        setMessage(`关闭工单失败：${error}`)
        return
      }
      await openTicket(selectedTicketId)
      await loadTickets(true)
      setMessage(response.body?.message ?? '工单已关闭。')
    } catch (err) {
      setMessage(err instanceof Error ? err.message : String(err))
    } finally {
      setClosing(false)
    }
  }

  function getStatusLabel(statusVal: string) {
    // 0 = open, 1 = closed
    if (statusVal === '0') {
      return { text: '进行中', className: 'bg-emerald-500/10 text-emerald-500 border border-emerald-500/25' }
    }
    if (statusVal === '1') {
      return { text: '已关闭', className: 'bg-rose-500/10 text-rose-500 border border-rose-500/25' }
    }
    return { text: statusVal || '进行中', className: 'bg-emerald-500/10 text-emerald-500 border border-emerald-500/25' }
  }

  const selectedTicket = tickets.find(t => rowId(t) === selectedTicketId)
  const isClosed = selectedTicket ? String(selectedTicket.status) === '1' : false

  return (
    <main className="mx-auto flex w-full max-w-6xl flex-col gap-6 px-4 pb-24 md:pb-8 pt-[calc(1.5rem+env(safe-area-inset-top,0px))]">
      {/* Banner */}
      <section className="rounded-3xl bg-surface-low p-6 shadow-md border border-outline-variant/40 relative overflow-hidden flex flex-col md:flex-row justify-between items-start md:items-center gap-4">
        <div className="space-y-1">
          <p className="text-[10px] font-extrabold uppercase tracking-widest text-primary">{appName}</p>
          <h1 className="text-xl font-extrabold tracking-tight text-on-background">工单中心 / Support Tickets</h1>
          <p className="text-xs text-on-surface-variant font-medium">有任何使用问题都可以随时创建工单，我们的客服将尽快答复您的提问。</p>
        </div>
        <button
          onClick={() => setShowCreateModal(true)}
          className="rounded-xl bg-primary px-4 py-2.5 text-xs font-bold text-white shadow-md hover:bg-primary/95 active:scale-95 transition-all flex items-center gap-1.5 cursor-pointer shrink-0"
        >
          <svg className="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2.5">
            <path strokeLinecap="round" strokeLinejoin="round" d="M12 4v16m8-8H4" />
          </svg>
          创建工单
        </button>
      </section>

      {message && (
        <div className="rounded-xl bg-primary/10 border border-primary/20 p-3 text-xs font-bold text-primary flex items-center justify-between">
          <span>ℹ️ {message}</span>
          <button onClick={() => setMessage('')} className="text-on-surface-variant hover:text-primary transition-colors cursor-pointer">✕</button>
        </div>
      )}

      {/* Main workspace */}
      <section className="grid gap-6 md:grid-cols-5 min-h-[500px]">
        {/* Left Column: Ticket List */}
        <section className="md:col-span-2 rounded-2xl bg-surface-low p-4 shadow-sm border border-outline-variant/40 flex flex-col max-h-[600px]">
          <div className="pb-3 border-b border-outline-variant/20 mb-3 flex items-center justify-between">
            <h2 className="text-sm font-extrabold text-on-background">我的工单列表 ({tickets.length})</h2>
            <button onClick={() => void loadTickets(false)} className="text-xs text-primary font-bold hover:underline cursor-pointer">刷新</button>
          </div>

          {loading ? (
            <div className="flex-1 flex items-center justify-center p-8">
              <div className="h-6 w-6 animate-spin rounded-full border-2 border-primary border-t-transparent"></div>
            </div>
          ) : tickets.length === 0 ? (
            <div className="flex-1 flex flex-col items-center justify-center text-center p-8 text-on-surface-variant">
              <svg className="h-12 w-12 text-on-surface-variant/40 mb-2" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="1.5">
                <path strokeLinecap="round" strokeLinejoin="round" d="M20 13V6a2 2 0 00-2-2H6a2 2 0 00-2 2v7m16 0a2 2 0 01-2 2H6a2 2 0 01-2-2m16 0V9a2 2 0 00-2-2H6a2 2 0 00-2 2v2m15 4h.01m-1.89 0h.01M12 16h.01M9 16h.01M6 16h.01M6 12h.01M9 12h.01" />
              </svg>
              <p className="text-xs font-semibold">暂无工单记录</p>
            </div>
          ) : (
            <ul className="flex-1 overflow-y-auto space-y-2.5 pr-1">
              {tickets.map((ticket, index) => {
                const id = rowId(ticket)
                const isSelected = selectedTicketId === id
                const subject = field(ticket, ['subject', 'title']) || `工单 #${id}`
                const updatedTime = timeText(ticket)
                const statusInfo = getStatusLabel(String(ticket.status))

                return (
                  <li
                    key={id || index}
                    onClick={() => void openTicket(id)}
                    className={`rounded-xl p-3 border transition-all duration-150 cursor-pointer flex flex-col gap-1.5 ${
                      isSelected
                        ? 'bg-primary/10 border-primary/30'
                        : 'bg-surface border-outline-variant/30 hover:border-primary/20'
                    }`}
                  >
                    <div className="flex items-start justify-between gap-2">
                      <p className={`font-bold text-xs truncate flex-1 ${isSelected ? 'text-primary' : 'text-on-background'}`}>
                        {subject}
                      </p>
                      <span className={`px-2 py-0.5 rounded-full text-[9px] font-extrabold ${statusInfo.className}`}>
                        {statusInfo.text}
                      </span>
                    </div>
                    <div className="flex items-center justify-between text-[10px] text-on-surface-variant font-medium">
                      <span># {id}</span>
                      <span>{updatedTime}</span>
                    </div>
                  </li>
                )
              })}
            </ul>
          )}
        </section>

        {/* Right Column: Chat/Messages Detail */}
        <section className="md:col-span-3 rounded-2xl bg-surface-low p-4 shadow-sm border border-outline-variant/40 flex flex-col max-h-[600px]">
          {selectedTicketId ? (
            <div className="flex flex-col h-full flex-1">
              {/* Detail Header */}
              <div className="pb-3 border-b border-outline-variant/20 mb-3 flex items-center justify-between gap-4">
                <div className="min-w-0">
                  <h3 className="text-sm font-extrabold text-on-background truncate">
                    {selectedTicket ? field(selectedTicket, ['subject', 'title']) : `工单 #${selectedTicketId}`}
                  </h3>
                  <p className="text-[10px] text-on-surface-variant font-medium mt-0.5">工单号: {selectedTicketId}</p>
                </div>
                {!isClosed && (
                  <button
                    onClick={closeTicket}
                    disabled={closing}
                    className="rounded-lg bg-rose-500/10 px-3 py-1.5 text-xs font-bold text-rose-500 border border-rose-500/20 hover:bg-rose-500/20 transition-all cursor-pointer shrink-0 disabled:opacity-40"
                  >
                    {closing ? '正在关闭...' : '关闭工单'}
                  </button>
                )}
              </div>

              {/* Chat View */}
              {loadingDetail ? (
                <div className="flex-1 flex items-center justify-center p-8">
                  <div className="h-6 w-6 animate-spin rounded-full border-2 border-primary border-t-transparent"></div>
                </div>
              ) : (
                <div className="flex-1 overflow-y-auto space-y-3 mb-4 pr-1">
                  {detailRows.map((msg, index) => {
                    const msgRole = field(msg, ['role', 'user_name', 'email']).toLowerCase()
                    const isStaff = msgRole.includes('admin') || msgRole.includes('staff') || msgRole.includes('support')
                    const senderName = isStaff ? '客服支持' : (field(msg, ['user_name', 'email']) || '我')
                    const content = field(msg, ['message', 'content', 'reply'])
                    const time = timeText(msg)

                    return (
                      <div
                        key={rowId(msg) || index}
                        className={`flex flex-col max-w-[85%] ${isStaff ? 'self-start mr-auto' : 'self-end ml-auto'}`}
                      >
                        <div className={`text-[10px] text-on-surface-variant mb-1 font-bold ${isStaff ? 'text-left' : 'text-right'}`}>
                          {senderName} · <span className="font-mono font-medium">{time}</span>
                        </div>
                        <div className={`rounded-2xl px-3.5 py-2.5 text-xs whitespace-pre-wrap leading-relaxed shadow-xs border ${
                          isStaff
                            ? 'bg-surface text-on-background border-outline-variant/35 rounded-tl-xs'
                            : 'bg-primary text-white border-primary/20 rounded-tr-xs'
                        }`}>
                          {content}
                        </div>
                      </div>
                    );
                  })}
                </div>
              )}

              {/* Reply Form */}
              {!isClosed ? (
                <form className="mt-auto space-y-2 border-t border-outline-variant/20 pt-3" onSubmit={replyTicket}>
                  <textarea
                    className="w-full rounded-xl bg-surface px-3 py-2.5 text-xs outline-none border border-outline-variant/50 focus:border-primary/50 transition-colors"
                    placeholder="请输入回复问题的内容..."
                    rows={3}
                    value={ticketReply}
                    onChange={(event) => setTicketReply(event.target.value)}
                    required
                    disabled={replying}
                  />
                  <div className="flex justify-end">
                    <button
                      className="rounded-xl bg-primary px-4 py-2 text-xs font-bold text-white shadow hover:bg-primary/95 active:scale-95 transition-all cursor-pointer disabled:opacity-40 flex items-center gap-1"
                      type="submit"
                      disabled={replying}
                    >
                      {replying && <div className="h-3 w-3 animate-spin rounded-full border-2 border-white border-t-transparent mr-1"></div>}
                      回复工单
                    </button>
                  </div>
                </form>
              ) : (
                <div className="mt-auto rounded-xl bg-on-surface-variant/5 p-3 text-center text-xs text-on-surface-variant border border-outline-variant/20">
                  🔒 该工单已被关闭，无法继续回复。
                </div>
              )}
            </div>
          ) : (
            <div className="flex-1 flex flex-col items-center justify-center text-center p-8 text-on-surface-variant">
              <svg className="h-14 w-14 text-on-surface-variant/30 mb-2.5" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="1.5">
                <path strokeLinecap="round" strokeLinejoin="round" d="M8 12h.01M12 12h.01M16 12h.01M21 12c0 4.418-4.03 8-9 8a9.863 9.863 0 01-4.255-.949L3 20l1.395-3.72C3.512 15.042 3 13.574 3 12c0-4.418 4.03-8 9-8s9 3.582 9 8z" />
              </svg>
              <p className="text-xs font-extrabold text-on-background">查看工单明细</p>
              <p className="text-[10px] mt-1">请从左侧列表中选择一个工单来查看详细记录和回复客服。</p>
            </div>
          )}
        </section>
      </section>

      {/* Create Ticket Modal */}
      {showCreateModal && (
        <div className="fixed inset-0 z-50 bg-black/60 backdrop-blur-xs flex items-center justify-center p-4">
          <form className="bg-surface-low border border-outline-variant/40 rounded-3xl w-full max-w-md p-5 flex flex-col shadow-2xl relative" onSubmit={createTicket}>
            <header className="flex items-center justify-between pb-3.5 border-b border-outline-variant/20 mb-4">
              <h2 className="text-base font-extrabold text-on-background">新建服务工单</h2>
              <button
                type="button"
                onClick={() => setShowCreateModal(false)}
                className="h-8 w-8 rounded-full bg-surface-variant flex items-center justify-center text-on-surface-variant hover:bg-rose-500 hover:text-white transition-all cursor-pointer"
              >
                <svg className="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2.5">
                  <path strokeLinecap="round" strokeLinejoin="round" d="M6 18L18 6M6 6l12 12" />
                </svg>
              </button>
            </header>

            <div className="space-y-3.5 mb-5">
              <div className="space-y-1">
                <label className="text-[10px] font-extrabold text-primary uppercase">主题 / Subject</label>
                <input
                  className="w-full rounded-xl bg-surface px-3 py-2 text-xs outline-none border border-outline-variant/50 focus:border-primary/50 transition-colors"
                  placeholder="如：节点无法连接、套餐状态异常"
                  value={ticketSubject}
                  onChange={(event) => setTicketSubject(event.target.value)}
                  required
                  disabled={submitting}
                />
              </div>

              <div className="space-y-1">
                <label className="text-[10px] font-extrabold text-primary uppercase">问题描述 / Message</label>
                <textarea
                  className="w-full rounded-xl bg-surface px-3 py-2 text-xs outline-none border border-outline-variant/50 focus:border-primary/50 transition-colors"
                  placeholder="请详细描述您遇到的问题或故障现象，以便客服能快速为您定位问题。"
                  rows={4}
                  value={ticketMessage}
                  onChange={(event) => setTicketMessage(event.target.value)}
                  required
                  disabled={submitting}
                />
              </div>
            </div>

            <footer className="flex justify-end gap-2.5">
              <button
                type="button"
                onClick={() => setShowCreateModal(false)}
                className="rounded-xl border border-outline-variant/60 px-4 py-2 text-xs font-bold text-on-surface hover:bg-on-surface-variant/5 transition-all cursor-pointer"
                disabled={submitting}
              >
                取消
              </button>
              <button
                type="submit"
                className="rounded-xl bg-primary px-4 py-2 text-xs font-bold text-white shadow hover:bg-primary/95 transition-all cursor-pointer flex items-center gap-1 disabled:opacity-40"
                disabled={submitting}
              >
                {submitting && <div className="h-3 w-3 animate-spin rounded-full border-2 border-white border-t-transparent mr-1"></div>}
                提交工单
              </button>
            </footer>
          </form>
        </div>
      )}
    </main>
  )
}
