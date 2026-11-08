const androidApps = new Set(['com.starpilot.galaxy'])
const defaultIosApps = 'link.galaxy.bluetooth.natehuby76'
export const iosApps = env => new Set(String(env?.IOS_APP_IDS ?? defaultIosApps).split(',').map(id => id.trim()).filter(Boolean))
const kinds = new Set(['warning', 'alarm', 'battery', 'test'])
const encode = value => new TextEncoder().encode(value)
const b64 = bytes => btoa(String.fromCharCode(...new Uint8Array(bytes))).replaceAll('+', '-').replaceAll('/', '_').replaceAll('=', '')
const json = (status, value) => Response.json(value, { status, headers: { 'Cache-Control': 'no-store' } })
let oauth = null
const imported = new Map()

async function importApns(body, token, fetcher) {
  const apns = /^apns\.(production|sandbox)\.([a-f0-9]{32,512})$/.exec(body.token)
  if (!apns || apns[2].length % 2) return null
  const key = `${body.appId}:${body.token}`
  if (imported.has(key)) return imported.get(key)
  const response = await fetcher('https://iid.googleapis.com/iid/v1:batchImport', { method: 'POST', redirect: 'manual', signal: AbortSignal.timeout(5000),
    headers: { Authorization: `Bearer ${token}`, access_token_auth: 'true', 'Content-Type': 'application/json' },
    body: JSON.stringify({ application: body.appId, sandbox: apns[1] === 'sandbox', apns_tokens: [apns[2]] }) })
  if (!response.ok) throw new Error('Token import unavailable')
  const result = (await response.json()).results?.[0]
  if (result?.status !== 'OK' || typeof result.registration_token !== 'string') throw new Error('Token import failed')
  if (imported.size >= 1000) imported.clear()
  imported.set(key, result.registration_token)
  return result.registration_token
}

export function valid(body, ios = new Set()) {
  return body !== null && typeof body === 'object' && !Array.isArray(body) &&
    Object.keys(body).sort().join(',') === (body.kind === 'battery' ? 'appId,detail,eventId,kind,label,token,wallTimeNs' : 'appId,eventId,kind,label,token,wallTimeNs') &&
    (body.kind !== 'battery' || typeof body.detail === 'string' && body.detail.length > 0 && body.detail.length <= 160 && !/[\x00-\x1f\x7f]/.test(body.detail)) &&
    typeof body.token === 'string' && /^[^\s\x00-\x1f\x7f]{1,4096}$/.test(body.token) && (androidApps.has(body.appId) || ios.has(body.appId)) && kinds.has(body.kind) &&
    typeof body.eventId === 'string' && /^[A-Za-z0-9_-]{1,64}$/.test(body.eventId) &&
    typeof body.wallTimeNs === 'number' && Number.isInteger(body.wallTimeNs) && body.wallTimeNs > 0 && body.wallTimeNs < 2 ** 63 &&
    typeof body.label === 'string' && body.label.length <= 64 && !/[\x00-\x1f\x7f]/.test(body.label)
}

async function accessToken(env, fetcher) {
  const account = JSON.parse(env.FCM_SERVICE_ACCOUNT)
  const now = Math.floor(Date.now() / 1000)
  if (oauth?.email === account.client_email && oauth.until > now) return oauth.token
  const header = b64(encode(JSON.stringify({ alg: 'RS256', typ: 'JWT' })))
  const payload = b64(encode(JSON.stringify({ iss: account.client_email, scope: 'https://www.googleapis.com/auth/firebase.messaging',
    aud: 'https://oauth2.googleapis.com/token', iat: now, exp: now + 3600 })))
  const raw = Uint8Array.from(atob(account.private_key.replace(/-----[^-]+-----|\s/g, '')), c => c.charCodeAt(0))
  const key = await crypto.subtle.importKey('pkcs8', raw, { name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256' }, false, ['sign'])
  const assertion = `${header}.${payload}.${b64(await crypto.subtle.sign('RSASSA-PKCS1-v1_5', key, encode(`${header}.${payload}`)))}`
  const response = await fetcher('https://oauth2.googleapis.com/token', { method: 'POST',
    body: new URLSearchParams({ grant_type: 'urn:ietf:params:oauth:grant-type:jwt-bearer', assertion }), signal: AbortSignal.timeout(5000) })
  if (!response.ok) throw new Error('OAuth unavailable')
  const result = await response.json()
  if (typeof result.access_token !== 'string') throw new Error('OAuth unavailable')
  oauth = { email: account.client_email, token: result.access_token, until: now + Math.min(3300, Number(result.expires_in) || 0) }
  return oauth.token
}

const titles = { alarm: 'Sentry alarm', warning: 'Sentry motion', battery: 'Sentry: battery low', test: 'Galaxy test alert' }

export function fcmMessage(body, ios = false) {
  const data = { type: 'sentry', kind: body.kind, eventId: body.eventId, wallTimeNs: String(body.wallTimeNs), label: body.label,
    ...(body.detail ? { detail: body.detail } : {}) }
  if (!ios) return { message: { token: body.token, android: { priority: 'HIGH', ttl: '600s', restricted_package_name: body.appId }, data } }
  return { message: { token: body.token, data,
    notification: { title: titles[body.kind], body: body.detail || body.label || 'Parked motion detected' },
    apns: { headers: { 'apns-priority': '10', 'apns-push-type': 'alert', 'apns-expiration': String(Math.floor(Date.now() / 1000) + 600), 'apns-collapse-id': body.eventId },
      payload: { aps: { sound: 'default', 'thread-id': 'sentry', 'mutable-content': 1 } } } } }
}

export async function handle(request, env, fetcher = fetch, authorize = accessToken) {
  if (request.method !== 'POST' || new URL(request.url).pathname !== '/v1/sentry') return json(404, { error: 'Not found' })
  let body
  try {
    const reader = request.body?.getReader()
    if (!reader) return json(400, { error: 'Bad body' })
    const chunks = []; let length = 0
    while (true) {
      const { value, done } = await reader.read()
      if (done) break
      length += value.length
      if (length > 8192) { await reader.cancel(); return json(400, { error: 'Bad body' }) }
      chunks.push(value)
    }
    const bytes = new Uint8Array(length); let offset = 0
    for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.length }
    body = JSON.parse(new TextDecoder().decode(bytes))
    if (!valid(body, iosApps(env))) return json(400, { error: 'Bad body' })
  } catch { return json(400, { error: 'Bad body' }) }
  try {
    const hash = b64(await crypto.subtle.digest('SHA-256', encode(body.token)))
    const hour = Math.floor(Date.now() / 3600000)
    const key = `${hash}:${hour}`
    const count = Number(await env.RATE.get(key)) || 0
    if (count >= 30) return json(429, { error: 'Rate limited' })
    await env.RATE.put(key, String(count + 1), { expirationTtl: 7200 })
    if (!/^[a-z0-9][a-z0-9-]{4,61}[a-z0-9]$/.test(env.FCM_PROJECT_ID)) throw new Error('Invalid project')
    const ios = iosApps(env).has(body.appId)
    const token = await authorize(env, fetcher)
    let destination = body.token
    if (ios && body.token.startsWith('apns.')) {
      destination = await importApns(body, token, fetcher)
      if (!destination) return json(400, { error: 'Invalid Apple token' })
    }
    const response = await fetcher(`https://fcm.googleapis.com/v1/projects/${env.FCM_PROJECT_ID}/messages:send`, {
      method: 'POST', headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
      body: JSON.stringify(fcmMessage({ ...body, token: destination }, ios)), signal: AbortSignal.timeout(5000), redirect: 'manual',
    })
    if (response.ok) return json(200, { ok: true })
    const failure = await response.json().catch(() => ({}))
    const codes = (failure.error?.details || []).filter(detail => detail['@type'] === 'type.googleapis.com/google.firebase.fcm.v1.FcmError').map(detail => detail.errorCode)
    if (codes.includes('UNREGISTERED') || codes.includes('INVALID_ARGUMENT')) { imported.delete(`${body.appId}:${body.token}`); return json(410, { error: 'Token expired' }) }
    return json(response.status === 429 ? 429 : 502, { error: 'FCM unavailable' })
  } catch { return json(502, { error: 'Relay unavailable' }) }
}

