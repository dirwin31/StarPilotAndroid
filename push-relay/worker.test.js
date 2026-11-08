import test from 'node:test'
import assert from 'node:assert/strict'
import worker, { workerFetch } from './worker.js'
const secret = 'a'.repeat(64)
const env = { RELAY_PATH_SECRET: secret, INGRESS: { limit: async () => ({ success: true }) } }
const request = (path = `/${secret}/v1/sentry`, method = 'POST') => new Request(`https://worker.test${path}`, { method, ...(method === 'POST' ? { body: '{"kind":"test"}' } : {}) })

test('Worker requires secret path and forwards an unchanged body to existing relay contract', async () => {
  const response = await workerFetch(request(), env, async (req, bindings) => {
    assert.equal(new URL(req.url).pathname, '/v1/sentry')
    assert.equal(await req.text(), '{"kind":"test"}')
    assert.equal(bindings, env)
    return new Response(null, { status: 200 })
  })
  assert.equal(response.status, 200)
  for (const req of [request('/v1/sentry'), request(`/${'b'.repeat(64)}/v1/sentry`), request(`/${secret}/v1/sentry?token=x`), request(`/${secret}/v1/sentry`, 'GET')]) {
    assert.equal((await workerFetch(req, env, () => assert.fail('must reject'))).status, 404)
  }
})

test('Worker fails closed for missing configuration and rate limits before processing tokens', async () => {
  assert.equal((await workerFetch(request(), {})).status, 503)
  assert.equal((await workerFetch(request(), { ...env, INGRESS: { limit: async () => ({ success: false }) } }, () => assert.fail('must rate limit'))).status, 429)
  assert.equal((await workerFetch(request(), { RELAY_PATH_SECRET: secret })).status, 502)
  assert.equal((await worker.fetch(request(), env, { waitUntil() {} })).status, 400)
})
