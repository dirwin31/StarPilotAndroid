import { handle } from './relay.js'
export { EnrollmentRegistry } from './enrollment.js'

export async function workerFetch(request, env, relay = handle) {
  const route = new URL(request.url)
  if (route.pathname.startsWith('/v2/')) {
    if (route.protocol !== 'https:' || route.search) return new Response('Invalid URL', { status: 400 })
    try {
      if (!(await env.INGRESS.limit({ key: request.headers.get('CF-Connecting-IP') || 'unknown' })).success) return new Response('Rate limited', { status: 429 })
      return await env.ENROLLMENT.get(env.ENROLLMENT.idFromName('registry')).fetch(request)
    } catch { return new Response('Relay unavailable', { status: 503 }) }
  }
  const secret = env.RELAY_PATH_SECRET
  if (typeof secret !== 'string' || !/^[a-f0-9]{64}$/.test(secret)) return new Response('Relay not configured', { status: 503 })
  const url = new URL(request.url)
  if (request.method !== 'POST' || url.pathname !== `/${secret}/v1/sentry` || url.search) return new Response('Not found', { status: 404 })
  try {
    const ip = request.headers.get('CF-Connecting-IP') || 'unknown'
    if (!(await env.INGRESS.limit({ key: ip })).success) return new Response('Rate limited', { status: 429 })
    url.pathname = '/v1/sentry'
    return await relay(new Request(url, request), env)
  } catch { return new Response('Relay unavailable', { status: 502 }) }
}

export default { fetch: (request, env) => workerFetch(request, env) }
