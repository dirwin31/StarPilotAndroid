import http from 'node:http'
import { readFileSync } from 'node:fs'
import { pathToFileURL } from 'node:url'
import { handle } from './relay.js'

export function memoryRate(now = Date.now) {
  const values = new Map()
  const prune = () => { const t = now(); for (const [key, entry] of values) if (entry.until <= t) values.delete(key) }
  return {
    async get(key) { const entry = values.get(key); return entry && entry.until > now() ? entry.value : undefined },
    async put(key, value, { expirationTtl = 7200 } = {}) { if (values.size > 100000) prune(); values.set(key, { value, until: now() + expirationTtl * 1000 }) },
    prune,
  }
}

export function createServer(env, fetcher = fetch, authorize) {
  return http.createServer(async (req, res) => {
    const chunks = []; let length = 0
    for await (const chunk of req) {
      length += chunk.length
      if (length > 8192) { res.writeHead(400, { 'Cache-Control': 'no-store' }).end('{"error":"Bad body"}'); req.destroy(); return }
      chunks.push(chunk)
    }
    const request = new Request('http://relay.local' + req.url, { method: req.method, body: req.method === 'POST' ? Buffer.concat(chunks) : undefined })
    const response = await (authorize ? handle(request, env, fetcher, authorize) : handle(request, env, fetcher))
    res.writeHead(response.status, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' }).end(await response.text())
  })
}

if (import.meta.url === pathToFileURL(process.argv[1]).href) {
  const file = process.env.FCM_SERVICE_ACCOUNT_FILE
  const env = { FCM_PROJECT_ID: process.env.FCM_PROJECT_ID, IOS_APP_IDS: process.env.IOS_APP_IDS, FCM_SERVICE_ACCOUNT: file ? readFileSync(file, 'utf8') : process.env.FCM_SERVICE_ACCOUNT, RATE: memoryRate() }
  if (!env.FCM_PROJECT_ID || !env.FCM_SERVICE_ACCOUNT) { console.error('Set FCM_PROJECT_ID and FCM_SERVICE_ACCOUNT_FILE'); process.exit(1) }
  setInterval(() => env.RATE.prune(), 600000).unref()
  const host = process.env.HOST || '127.0.0.1', port = Number(process.env.PORT) || 8787
  createServer(env).listen(port, host, () => console.log(`galaxy-push-relay listening on ${host}:${port}`))
}
