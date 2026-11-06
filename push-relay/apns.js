const encode = value => new TextEncoder().encode(value)
const b64 = bytes => btoa(String.fromCharCode(...new Uint8Array(bytes))).replaceAll('+', '-').replaceAll('/', '_').replaceAll('=', '')
const json = (status, error) => Response.json(error ? { error } : { ok: true }, { status, headers: { 'Cache-Control': 'no-store' } })
let cached

export async function apnsAuthorization(env) {
  const now = Math.floor(Date.now() / 1000)
  if (cached?.key === env.APNS_PRIVATE_KEY && cached.team === env.APNS_TEAM_ID && cached.id === env.APNS_KEY_ID && cached.until > now) return cached.jwt
  if (!/^[A-Z0-9]{10}$/.test(env.APNS_TEAM_ID) || !/^[A-Z0-9]{10}$/.test(env.APNS_KEY_ID)) throw new Error('APNs configuration')
  const raw = Uint8Array.from(atob(env.APNS_PRIVATE_KEY.replace(/-----[^-]+-----|\s/g, '')), c => c.charCodeAt(0))
  const key = await crypto.subtle.importKey('pkcs8', raw, { name: 'ECDSA', namedCurve: 'P-256' }, false, ['sign'])
  const unsigned = `${b64(encode(JSON.stringify({ alg: 'ES256', kid: env.APNS_KEY_ID })))}.${b64(encode(JSON.stringify({ iss: env.APNS_TEAM_ID, iat: now })))}`
  const signature = await crypto.subtle.sign({ name: 'ECDSA', hash: 'SHA-256' }, key, encode(unsigned))
  const jwt = `${unsigned}.${b64(signature)}`
  cached = { key: env.APNS_PRIVATE_KEY, team: env.APNS_TEAM_ID, id: env.APNS_KEY_ID, jwt, until: now + 3000 }
  return jwt
}

export async function sendAPNs(body, env, fetcher = fetch, authorize = apnsAuthorization) {
  const token = /^apns\.(production|sandbox)\.([a-f0-9]{32,512})$/.exec(body.token)
  if (!token || token[2].length % 2) return json(400, 'Invalid Apple token')
  const host = token[1] === 'sandbox' ? 'api.sandbox.push.apple.com' : 'api.push.apple.com'
  const title = { alarm: 'Sentry alarm', warning: 'Sentry: motion detected', test: 'Galaxy test alert' }[body.kind]
  const response = await fetcher(`https://${host}/3/device/${token[2]}`, {
    method: 'POST', redirect: 'manual', signal: AbortSignal.timeout(5000),
    headers: { authorization: `bearer ${await authorize(env)}`, 'content-type': 'application/json',
      'apns-topic': body.appId, 'apns-push-type': 'alert', 'apns-priority': '10',
      'apns-expiration': String(Math.floor(Date.now() / 1000) + 600), 'apns-collapse-id': body.eventId },
    body: JSON.stringify({ aps: { alert: { title, body: body.label || 'Parked motion detected' }, sound: 'default', 'thread-id': 'sentry' },
      type: 'sentry', kind: body.kind, eventId: body.eventId, wallTimeNs: String(body.wallTimeNs) }),
  })
  if (response.ok) return json(200)
  const failure = await response.json().catch(() => ({}))
  if (response.status === 410 && failure.reason === 'Unregistered') return json(410, 'Token expired')
  return json(response.status === 429 ? 429 : 502, 'APNs unavailable')
}
