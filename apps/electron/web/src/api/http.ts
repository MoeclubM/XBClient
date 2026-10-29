import { invoke } from '../platform/electron'
import { publicErrorText } from '../format'

export interface HttpResponse<T = unknown> {
  ok: boolean
  status: number
  body: T
  error?: string
}

export async function httpRequest<T>(request: { method: string; url: string; headers: Record<string, string>; body?: unknown }): Promise<HttpResponse<T>> {
  try { return await invoke<HttpResponse<T>>('http_request', { request }) }
  catch (error) { throw new Error(publicErrorText(error, 'Request failed')) }
}
