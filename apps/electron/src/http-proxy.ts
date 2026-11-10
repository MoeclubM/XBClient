import http from 'node:http'
import https from 'node:https'
import type { Socket } from 'node:net'
import type { Duplex } from 'node:stream'
import { SocksClient } from 'socks'
import { SocksProxyAgent } from 'socks-proxy-agent'

export interface HttpProxyOptions {
  listenPort?: number
  allowLan?: boolean
}

export interface HttpProxyBridge {
  port: number
  listenPort: number
  allowLan: boolean
  setUpstream: (host: string, port: number) => void
  close: () => Promise<void>
}

function buildSocksAgent(host: string, port: number) {
  return new SocksProxyAgent(`socks5h://${host.includes(':') ? `[${host}]` : host}:${port}`, { keepAlive: true })
}

export async function startHttpProxy(host: string, port: number, options: HttpProxyOptions = {}): Promise<HttpProxyBridge> {
  const listenPort = options.listenPort && options.listenPort > 0 ? options.listenPort : 0
  const allowLan = Boolean(options.allowLan)
  const listenHost = allowLan ? '0.0.0.0' : '127.0.0.1'
  const sockets = new Set<Socket>()
  let upstreamHost = host
  let upstreamPort = port
  let agent = buildSocksAgent(host, port)
  function forward(request: http.IncomingMessage) {
    const target = new URL(request.url!, `http://${request.headers.host}`)
    if (target.protocol === 'ws:') target.protocol = 'http:'
    if (target.protocol !== 'http:' && target.protocol !== 'https:') {
      throw new Error('Unsupported proxy URL')
    }
    const headers: http.OutgoingHttpHeaders = { ...request.headers, host: target.host }
    delete headers['proxy-authorization']
    delete headers['proxy-connection']
    const transport = target.protocol === 'https:' ? https : http
    return transport.request(target, { method: request.method, headers, agent })
  }
  function tunnel(local: Duplex, socket: Socket, head: Buffer) {
    sockets.add(socket)
    socket.on('close', () => sockets.delete(socket))
    local.on('error', () => socket.destroy())
    socket.on('error', () => local.destroy())
    local.on('close', () => socket.destroy())
    socket.on('close', () => local.destroy())
    if (head.length) socket.write(head)
    local.pipe(socket)
    socket.pipe(local)
  }
  const server = http.createServer((request, response) => {
    let upstream: http.ClientRequest
    try {
      upstream = forward(request)
    } catch (error) {
      response.writeHead(400)
      response.end(String(error))
      return
    }
    upstream.on('response', (result) => {
      response.writeHead(result.statusCode!, result.headers)
      result.on('error', (error) => response.destroy(error))
      result.pipe(response)
    })
    upstream.on('error', (error) => {
      if (!response.headersSent) response.writeHead(502)
      response.end(error.message)
    })
    request.on('aborted', () => upstream.destroy())
    response.on('close', () => { if (!response.writableFinished) upstream.destroy() })
    request.pipe(upstream)
  })
  server.on('connection', (socket) => {
    sockets.add(socket)
    socket.on('close', () => sockets.delete(socket))
  })
  server.on('connect', (request, local, head) => {
    local.on('error', (error) => console.error('[HTTP CONNECT]', error.message))
    void (async () => {
      const target = new URL(`https://${request.url}`)
      const { socket } = await SocksClient.createConnection({
        proxy: { host: upstreamHost, port: upstreamPort, type: 5 },
        command: 'connect',
        destination: { host: target.hostname.replace(/^\[|\]$/g, ''), port: Number(target.port) || 443 },
      })
      if (local.destroyed) { socket.destroy(); return }
      local.write('HTTP/1.1 200 Connection Established\r\n\r\n')
      tunnel(local, socket, head)
    })().catch((error: Error) => {
      local.end(`HTTP/1.1 502 Bad Gateway\r\nConnection: close\r\n\r\n${error.message}`)
    })
  })
  server.on('upgrade', (request, local, head) => {
    local.on('error', (error) => console.error('[HTTP upgrade]', error.message))
    let upstream: http.ClientRequest
    try {
      upstream = forward(request)
    } catch {
      local.end('HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\n')
      return
    }
    local.on('close', () => upstream.destroy())
    upstream.on('error', () => local.end('HTTP/1.1 502 Bad Gateway\r\nConnection: close\r\n\r\n'))
    upstream.on('response', (response) => {
      local.end(`HTTP/1.1 ${response.statusCode} ${response.statusMessage}\r\nConnection: close\r\n\r\n`)
      response.destroy()
    })
    upstream.on('upgrade', (response, socket, remoteHead) => {
      if (local.destroyed) { socket.destroy(); return }
      const headers = response.rawHeaders.reduce((lines, value, index) => lines + (index % 2 ? `${value}\r\n` : `${value}: `), '')
      local.write(`HTTP/1.1 ${response.statusCode} ${response.statusMessage}\r\n${headers}\r\n`)
      if (remoteHead.length) local.write(remoteHead)
      tunnel(local, socket, head)
    })
    upstream.end()
  })
  server.on('clientError', (_error, socket) => socket.end('HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\n'))
  await new Promise<void>((resolve, reject) => {
    const onError = (error: NodeJS.ErrnoException) => {
      if (error.code === 'EADDRINUSE' && listenPort > 0) {
        reject(new Error(`本地 HTTP 代理端口 ${listenPort} 已被占用，请在设置中更换端口或设为 0 自动分配`))
        return
      }
      reject(error)
    }
    server.once('error', onError)
    server.listen(listenPort, listenHost, () => {
      server.off('error', onError)
      resolve()
    })
  })
  return {
    port: (server.address() as { port: number }).port,
    listenPort,
    allowLan,
    setUpstream: (nextHost: string, nextPort: number) => {
      if (upstreamHost === nextHost && upstreamPort === nextPort) return
      upstreamHost = nextHost
      upstreamPort = nextPort
      const previous = agent
      agent = buildSocksAgent(nextHost, nextPort)
      previous.destroy()
    },
    close: async () => {
      agent.destroy()
      for (const socket of sockets) socket.destroy()
      await new Promise<void>((resolve, reject) => server.close((error) => error ? reject(error) : resolve()))
    },
  }
}
