import test from 'node:test'
import assert from 'node:assert/strict'
import { createServer, memoryRate } from './server.js'
const body = { token: 'private-token', appId: 'link.firestar.galaxy', kind: 'warning', eventId: 'abc', wallTimeNs: 1000000000, label: 'Phone' }
async function listen(env, fetcher) {
  const server = createServer(env, fetcher, async () => 'oauth')
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve))
  return { server, url: `http://127.0.0.1:${server.address().port}/v1/sentry` }
}
test('memory rate store expires entries', async () => {
  let t = 0
  const rate = memoryRate(() => t)
  await rate.put('k', '5', { expirationTtl: 10 })
  assert.equal(await rate.get('k'), '5')
  t = 10001
  assert.equal(await rate.get('k'), undefined)
})
test('HTTP server relays, rate limits and rejects oversize bodies', async () => {
  const { server, url } = await listen({ FCM_PROJECT_ID: 'galaxy-project', RATE: memoryRate() }, async () => Response.json({}))
  try {
    const post = payload => fetch(url, { method: 'POST', body: payload })
    assert.equal((await post(JSON.stringify(body))).status, 200)
    assert.equal((await post('x'.repeat(9000)).catch(() => ({ status: 400 }))).status, 400)
    assert.equal((await fetch(url)).status, 404)
    for (let i = 0; i < 29; i++) await post(JSON.stringify(body))
    assert.equal((await post(JSON.stringify(body))).status, 429)
  } finally { server.close() }
})
