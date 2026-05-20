import { useState, type FormEvent } from 'react'
import { xboardRequest } from '../../api/xboard'
import { failureText, field, rowId, timeText, type Row, type XboardBody } from './helpers'

interface Props {
  baseUrl: string
  authData: string
  loading: boolean
  sessions: Row[]
  setSessions(value: Row[] | ((current: Row[]) => Row[])): void
  setMessage(value: string): void
}

export function AccountSecurity({ baseUrl, authData, loading, sessions, setSessions, setMessage }: Props) {
  const [oldPassword, setOldPassword] = useState('')
  const [newPassword, setNewPassword] = useState('')

  async function changePassword(event: FormEvent) {
    event.preventDefault()
    setMessage('')
    const response = await xboardRequest<XboardBody>('change_password', {
      baseUrl,
      authData,
      params: { old_password: oldPassword, new_password: newPassword },
    })
    const error = failureText(response)
    if (error) {
      setMessage(`修改密码失败：${error}`)
      return
    }
    setOldPassword('')
    setNewPassword('')
    setMessage(response.body?.message ?? '密码已修改。')
  }

  async function resetSecurity() {
    setMessage('')
    const response = await xboardRequest<XboardBody>('reset_security', { baseUrl, authData })
    const error = failureText(response)
    setMessage(error ? `重置安全失败：${error}` : response.body?.message ?? '安全信息已重置。')
  }

  async function removeSession(session: Row) {
    setMessage('')
    const id = rowId(session)
    const response = await xboardRequest<XboardBody>('remove_active_session', { baseUrl, authData, params: { id, session_id: id } })
    const error = failureText(response)
    if (error) {
      setMessage(`移除会话失败：${error}`)
      return
    }
    setSessions((current) => current.filter((item) => rowId(item) !== id))
    setMessage(response.body?.message ?? '会话已移除。')
  }

  return (
    <section className="grid gap-4 md:grid-cols-2">
      <form onSubmit={changePassword} className="space-y-3 rounded-2xl bg-surface-low p-5 shadow-sm border border-outline-variant/40">
        <h2 className="text-sm font-bold tracking-tight text-primary">账号安全</h2>
        <input className="w-full rounded-xl bg-surface px-3 py-2 text-sm outline-none border border-outline-variant/50" type="password" placeholder="当前密码" value={oldPassword} onChange={(event) => setOldPassword(event.target.value)} required />
        <input className="w-full rounded-xl bg-surface px-3 py-2 text-sm outline-none border border-outline-variant/50" type="password" placeholder="新密码" value={newPassword} onChange={(event) => setNewPassword(event.target.value)} required />
        <div className="flex gap-2">
          <button className="rounded-xl bg-primary px-4 py-2 text-xs font-bold text-white" type="submit">修改密码</button>
          <button className="rounded-xl bg-rose-500/10 px-4 py-2 text-xs font-bold text-rose-500 border border-rose-500/20" type="button" onClick={() => void resetSecurity()}>重置安全</button>
        </div>
      </form>

      <section className="space-y-3 rounded-2xl bg-surface-low p-5 shadow-sm border border-outline-variant/40">
        <div className="flex items-center justify-between">
          <h2 className="text-sm font-bold tracking-tight text-primary">活跃会话</h2>
          {loading && (
            <div className="h-3.5 w-3.5 animate-spin rounded-full border border-primary border-t-transparent"></div>
          )}
        </div>
        <ul className="space-y-2 max-h-64 overflow-y-auto">
          {sessions.map((session, index) => (
            <li key={rowId(session) || index} className="rounded-xl bg-surface p-3 border border-outline-variant/25 text-xs">
              <div className="flex items-start justify-between gap-3">
                <div className="min-w-0 space-y-1">
                  <p className="font-bold text-on-background">{field(session, ['ip', 'last_ip', 'remote_addr']) || 'Session'}</p>
                  <p className="truncate text-on-surface-variant">{field(session, ['user_agent', 'device', 'browser'])}</p>
                  <p className="text-[10px] text-on-surface-variant">{timeText(session)}</p>
                </div>
                <button className="shrink-0 rounded-lg bg-rose-500/10 px-3 py-1.5 font-bold text-rose-500" onClick={() => void removeSession(session)}>移除</button>
              </div>
            </li>
          ))}
          {sessions.length === 0 && <li className="text-xs text-on-surface-variant">暂无活跃会话数据。</li>}
        </ul>
      </section>
    </section>
  )
}
