import { handle, valid, iosApps } from './relay.js'

const hex = { test: value => typeof value === 'string' && /^[a-f0-9]{64}$/.test(value) }
const idPattern = { test: value => typeof value === 'string' && /^[a-f0-9]{32}$/.test(value) }
const limit = (value, fallback) => /^\d+$/.test(String(value)) ? Math.max(1, Math.min(100000, Number(value))) : fallback
const reply = (status, error) => Response.json(typeof error === 'string' ? { error } : error, { status, headers: { 'Cache-Control': 'no-store' } })
export const digest = async value => Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(value))), n => n.toString(16).padStart(2, '0')).join('')
export function equal(a, b) {
  if (typeof a !== 'string' || typeof b !== 'string' || a.length !== b.length) return false
  let result = 0
  for (let i = 0; i < a.length; i++) result |= a.charCodeAt(i) ^ b.charCodeAt(i)
  return result === 0
}
const fields = (value, names) => value && !Array.isArray(value) && Object.keys(value).sort().join(',') === names.split(',').sort().join(',')
async function body(request) {
  const reader = request.body?.getReader()
  if (!reader) throw Error('body')
  let size = 0; const chunks = []
  for (;;) {
    const { done, value } = await reader.read()
    if (done) break
    size += value.length
    if (size > 8192) { await reader.cancel(); throw Error('body') }
    chunks.push(value)
  }
  const bytes = new Uint8Array(size); let offset = 0
  for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.length }
  return JSON.parse(new TextDecoder().decode(bytes))
}

export class EnrollmentRegistry {
  constructor(state, env) { this.storage = state.storage; this.env = env }

  async alarm() {
    for (const prefix of ['invite:', 'auto-ip:']) {
      let startAfter
      for (;;) {
        const rows = await this.storage.list({ prefix, limit: 128, ...(startAfter ? { startAfter } : {}) })
        if (!rows.size) break
        const expired = [...rows].filter(([, value]) => value.expires < Date.now()).map(([key]) => key)
        if (expired.length) await this.storage.delete(expired)
        startAfter = [...rows.keys()].at(-1)
        if (rows.size < 128) break
      }
    }
    await this.storage.setAlarm(Date.now() + 3600000)
  }

  async fetch(request) {
    try { return await this.route(request) } catch { return reply(503, 'Relay unavailable') }
  }

  async route(request) {
    const url = new URL(request.url), path = url.pathname, now = Date.now()
    if (url.search) return reply(400, 'Query parameters are not supported')
    let data = {}
    if (['POST', 'PUT'].includes(request.method)) {
      try { data = await body(request) } catch { return reply(400, 'Invalid body') }
    }
    if (path.startsWith('/v2/admin/')) {
      const secret = this.env.ENROLLMENT_ADMIN_SECRET
      if (!hex.test(secret || '') || !equal(request.headers.get('Authorization'), `Bearer ${secret}`)) return reply(403, 'Admin authorization required')
      if (path === '/v2/admin/invitations' && request.method === 'POST') {
        if (!fields(data, 'invitation') || !hex.test(data.invitation)) return reply(400, 'Invalid invitation')
        const key = `invite:${await digest(data.invitation)}`
        if (this.storage.setAlarm && !(await this.storage.getAlarm())) await this.storage.setAlarm(now + 3600000)
        return this.storage.transaction(async tx => {
          if (await tx.get(key)) return reply(409, 'Invitation already exists')
          await tx.put(key, { expires: now + 900000 })
          return reply(201, { expiresAt: now + 900000 })
        })
      }
      const match = path.match(/^\/v2\/admin\/installations\/([a-f0-9]{32})$/)
      if (match && request.method === 'DELETE') {
        await this.storage.put(`device:${match[1]}`, { revoked: true })
        return reply(200, { revoked: true })
      }
      return reply(404, 'Not found')
    }
    if (path === '/v2/enroll' && request.method === 'POST') {
      if (!fields(data, 'invitation,installationId,secretDigest,requestId') || !(data.invitation === '' || hex.test(data.invitation)) || !hex.test(data.secretDigest) || !idPattern.test(data.installationId) || !idPattern.test(data.requestId)) return reply(400, 'Invalid enrollment')
      if (data.invitation === '') {
        if (this.env.AUTO_ENROLL !== 'true' || !hex.test(this.env.ENROLLMENT_ADMIN_SECRET)) return reply(503, 'Automatic enrollment is not configured')
        const day = Math.floor(now / 86400000)
        const ipKey = `auto-ip:${await digest(day + ':' + (request.headers.get('CF-Connecting-IP') || 'unknown'))}`
        if (this.storage.setAlarm && !(await this.storage.getAlarm())) await this.storage.setAlarm(now + 3600000)
        return this.storage.transaction(async tx => {
          const deviceKey = `device:${data.installationId}`
          const existing = await tx.get(deviceKey)
          if (existing) return !existing.revoked && equal(existing.digest, data.secretDigest) && existing.enrollmentRequest === data.requestId
            ? reply(200, { enrolled: true }) : reply(409, 'Installation already exists or is revoked')
          const stats = await tx.get('auto-stats') || { day, count: 0, total: 0 }
          if (stats.day !== day) { stats.day = day; stats.count = 0 }
          const ip = await tx.get(ipKey) || { count: 0, expires: (day + 2) * 86400000 }
          if (stats.total >= limit(this.env.MAX_INSTALLATIONS, 1000) || stats.count >= limit(this.env.AUTO_ENROLL_DAILY_LIMIT, 100) || ip.count >= limit(this.env.AUTO_ENROLL_IP_DAILY_LIMIT, 5)) return reply(429, 'Enrollment capacity reached; retry later')
          stats.count++; stats.total++; ip.count++
          await tx.put('auto-stats', stats); await tx.put(ipKey, ip)
          await tx.put(deviceKey, { digest: data.secretDigest, enrollmentRequest: data.requestId, subscriptions: {}, hour: 0, count: 0, receipts: {} })
          return reply(201, { enrolled: true })
        })
      }
      const key = `invite:${await digest(data.invitation)}`
      return this.storage.transaction(async tx => {
        const invitation = await tx.get(key)
        if (!invitation || invitation.expires < now) return reply(403, 'Invitation expired or unavailable')
        const deviceKey = `device:${data.installationId}`
        if (invitation.used) {
          const current = await tx.get(deviceKey)
          return invitation.used === data.requestId && invitation.installationId === data.installationId && equal(current?.digest, data.secretDigest)
            ? reply(200, { enrolled: true }) : reply(403, 'Invitation already used')
        }
        if (await tx.get(deviceKey)) return reply(409, 'Installation already exists')
        await tx.put(deviceKey, { digest: data.secretDigest, subscriptions: {}, hour: 0, count: 0, receipts: {} })
        await tx.put(key, { used: data.requestId, installationId: data.installationId, expires: now + 86400000 })
        return reply(201, { enrolled: true })
      })
    }
    const auth = request.headers.get('Authorization')?.match(/^Bearer ([a-f0-9]{32})\.([a-f0-9]{64})$/)
    if (!auth) return reply(401, 'Credential required')
    const key = `device:${auth[1]}`, hash = await digest(auth[2])
    const result = await this.storage.transaction(async tx => {
      const device = await tx.get(key)
      if (!device || !(equal(device.digest, hash) || (device.previousUntil > now && equal(device.previous, hash)))) return reply(401, 'Credential invalid or revoked')
      if (path === '/v2/installation' && request.method === 'GET') return reply(200, { enrolled: true, current: equal(device.digest, hash) })
      if (path === '/v2/installation' && request.method === 'DELETE') { await tx.put(key, { revoked: true }); return reply(200, { revoked: true }) }
      if (path === '/v2/credentials/rotate' && request.method === 'POST') {
        if (!fields(data, 'secretDigest,requestId') || !hex.test(data.secretDigest) || !idPattern.test(data.requestId)) return reply(400, 'Invalid rotation')
        if (device.rotation === data.requestId) return equal(device.digest, data.secretDigest) ? reply(200, { rotated: true }) : reply(409, 'Rotation mismatch')
        if (!equal(device.digest, hash)) return reply(403, 'Current credential required')
        device.previous = device.digest; device.previousUntil = now + 600000
        device.digest = data.secretDigest; device.rotation = data.requestId
        await tx.put(key, device)
        return reply(200, { rotated: true })
      }
      if (path === '/v2/credentials/confirm' && request.method === 'POST') {
        if (!fields(data, '') || !equal(device.digest, hash)) return reply(403, 'Current credential required')
        delete device.previous; delete device.previousUntil
        await tx.put(key, device)
        return reply(200, { confirmed: true })
      }
      const sub = path.match(/^\/v2\/subscriptions\/([a-f0-9]{12,32})$/)
      if (sub && request.method === 'DELETE') {
        delete device.subscriptions[sub[1]]; await tx.put(key, device)
        return reply(200, { removed: true })
      }
      if (sub && request.method === 'PUT') {
        if (!fields(data, 'token,appId') || !valid({ ...data, kind: 'test', eventId: 'test', wallTimeNs: now * 1e6, label: '' }, iosApps(this.env))) return reply(400, 'Invalid destination')
        if (!device.subscriptions[sub[1]] && Object.keys(device.subscriptions).length >= 8) return reply(409, 'Destination limit')
        device.subscriptions[sub[1]] = await digest(`${data.appId}:${data.token}`)
        await tx.put(key, device)
        return reply(200, { registered: true })
      }
      if (path !== '/v2/sentry' || request.method !== 'POST') return reply(404, 'Not found')
      if (!valid(data, iosApps(this.env)) || Math.abs(now - data.wallTimeNs / 1e6) > 600000) return reply(400, 'Invalid or stale event')
      const fingerprint = await digest(`${data.appId}:${data.token}`)
      if (!Object.values(device.subscriptions).includes(fingerprint)) return reply(409, 'Destination not registered')
      const receipt = `${fingerprint}:${data.eventId}:${data.kind}`
      for (const [id, value] of Object.entries(device.receipts)) if (value.until < now) delete device.receipts[id]
      if (device.receipts[receipt]) return device.receipts[receipt].done ? reply(200, { duplicate: true }) : reply(429, 'Delivery in progress')
      const hour = Math.floor(now / 3600000)
      if (device.hour !== hour) { device.hour = hour; device.count = 0 }
      if (device.count >= 60) return reply(429, 'Installation quota exceeded')
      const day = Math.floor(now / 86400000)
      const global = await tx.get('send-budget') || { day, count: 0 }
      if (global.day !== day) { global.day = day; global.count = 0 }
      if (global.count >= limit(this.env.DAILY_SEND_LIMIT, 1000)) return reply(429, 'Daily relay capacity reached')
      global.count++; await tx.put('send-budget', global)
      device.count++
      device.receipts[receipt] = { until: now + 30000, done: false }
      await tx.put(key, device)
      return { receipt, fingerprint }
    })
    if (result instanceof Response) return result
    const provider = this.env.SEND || handle
    const response = await provider(new Request('https://relay.internal/v1/sentry', { method: 'POST', body: JSON.stringify(data) }), { ...this.env, RATE: { get: async () => null, put: async () => {} } })
    await this.storage.transaction(async tx => {
      const device = await tx.get(key)
      if (!device || device.revoked) return
      if (response.ok) device.receipts[result.receipt] = { until: now + 86400000, done: true }
      else delete device.receipts[result.receipt]
      if (response.status === 410) for (const [id, value] of Object.entries(device.subscriptions)) if (value === result.fingerprint) delete device.subscriptions[id]
      await tx.put(key, device)
    })
    return response
  }
}
