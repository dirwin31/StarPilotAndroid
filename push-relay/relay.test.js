import test from 'node:test'
import assert from 'node:assert/strict'
import { handle, valid, fcmMessage, iosApps } from './relay.js'
const body = { token: 'private-token', appId: 'com.starpilot.galaxy', kind: 'warning', eventId: 'abc', wallTimeNs: 1000000000, label: 'Phone' }
function request(value = body) { return new Request('https://relay.test/v1/sentry', { method: 'POST', body: JSON.stringify(value) }) }
function environment() {
  const values = new Map()
  return { FCM_PROJECT_ID: 'galaxy-project', RATE: { get: async key => values.get(key), put: async (key, value) => values.set(key, value) } }
}
test('strict schema and templated data-only payload', () => {
  assert.ok(valid(body))
  for (const value of [{ ...body, appId: 'evil.app' }, { ...body, appId: 'link.firestar.companion' }, { ...body, kind: 'arbitrary' }, { ...body, extra: true }, { ...body, token: 'a'.repeat(4097) }]) assert.equal(valid(value), false)
  const message = fcmMessage(body).message
  assert.equal(message.notification, undefined)
  assert.equal(message.android.priority, 'HIGH')
  assert.equal(message.data.type, 'sentry')
})
test('rate limiting and response mapping', async () => {
  const env = environment()
  // Cloudflare's fetch rejects redirect: 'error'.
  for (let i = 0; i < 30; i++) assert.equal((await handle(request(), env, async (url, init) => { assert.equal(init.redirect, 'manual'); return Response.json({}) }, async () => 'oauth')).status, 200)
  assert.equal((await handle(request(), env)).status, 429)
  for (const code of ['UNREGISTERED', 'INVALID_ARGUMENT']) {
    const failure = { error: { details: [{ '@type': 'type.googleapis.com/google.firebase.fcm.v1.FcmError', errorCode: code }] } }
    assert.equal((await handle(request(), environment(), async () => Response.json(failure, { status: 400 }), async () => 'oauth')).status, 410)
  }
  assert.equal((await handle(request(), environment(), async () => Response.json({ error: { status: 'INVALID_ARGUMENT' } }, { status: 400 }), async () => 'oauth')).status, 502)
})
test('invalid body never sends', async () => {
  assert.equal((await handle(request({ ...body, token: '' }), environment(), () => { throw Error('must not send') })).status, 400)
})
test('iOS app IDs get a visible APNs alert and Android stays data-only', () => {
  const ios = iosApps({ IOS_APP_IDS: 'link.galaxy.app' })
  const phone = { ...body, appId: 'link.galaxy.app' }
  assert.equal(valid(phone), false)
  assert.ok(valid(phone, ios))
  const message = fcmMessage(phone, true).message
  assert.equal(message.notification.title, 'Sentry motion')
  assert.equal(message.apns.headers['apns-push-type'], 'alert')
  assert.equal(message.apns.headers['apns-priority'], '10')
  assert.equal(message.android, undefined)
  assert.equal(message.data.type, 'sentry')
  assert.equal(fcmMessage(body).message.apns, undefined)
  assert.equal(fcmMessage(body).message.notification, undefined)
})
test('iOS IDs are configurable and can be disabled', () => {
  assert.equal(iosApps({ IOS_APP_IDS: '' }).size, 0)
  assert.deepEqual([...iosApps({ IOS_APP_IDS: 'a.b, c.d' })], ['a.b', 'c.d'])
})
