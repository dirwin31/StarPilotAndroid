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
test('Apple tokens are converted through Firebase and sent as visible alerts', async () => {
  const env = { ...environment(), IOS_APP_IDS: 'link.galaxy.bluetooth.natehuby76' }
  const phone = { ...body, appId: 'link.galaxy.bluetooth.natehuby76', token: `apns.sandbox.${'cd'.repeat(32)}` }
  const calls = []
  const fetcher = async (url, init) => {
    calls.push(url)
    assert.equal(init.redirect, 'manual')
    const sent = JSON.parse(init.body)
    if (url.startsWith('https://iid.googleapis.com/')) {
      assert.deepEqual(sent, { application: phone.appId, sandbox: true, apns_tokens: ['cd'.repeat(32)] })
      return Response.json({ results: [{ status: 'OK', registration_token: 'fcm-from-apns' }] })
    }
    assert.equal(sent.message.token, 'fcm-from-apns')
    assert.equal(sent.message.notification.title, 'Sentry motion')
    return Response.json({})
  }
  for (let i = 0; i < 2; i++) assert.equal((await handle(request(phone), env, fetcher, async () => 'oauth')).status, 200)
  assert.equal(calls.filter(url => url.startsWith('https://iid.googleapis.com/')).length, 1)
  const failed = async url => url.startsWith('https://iid.') ? Response.json({ results: [{ status: 'INTERNAL' }] }) : assert.fail('must not send')
  assert.equal((await handle(request({ ...phone, token: `apns.production.${'ef'.repeat(32)}` }), env, failed, async () => 'oauth')).status, 502)
  assert.equal((await handle(request({ ...phone, token: 'apns.production.xyz' }), env, () => assert.fail('must not send'), async () => 'oauth')).status, 400)
})
test('battery alerts carry their voltage text to Android data and the iPhone body', () => {
  const battery = { ...body, kind: 'battery', detail: 'Car battery is low (12.0 V). The device and Sentry turn off at 11.8 V.' }
  assert.ok(valid(battery))
  for (const value of [{ ...body, kind: 'battery' }, { ...body, detail: battery.detail }, { ...battery, detail: '' }, { ...battery, detail: 'a\nb' }, { ...battery, detail: 'a'.repeat(161) }])
    assert.equal(valid(value), false)
  assert.equal(fcmMessage(battery).message.data.detail, battery.detail)
  const phone = fcmMessage({ ...battery, appId: 'link.galaxy.bluetooth.natehuby76' }, true).message
  assert.equal(phone.notification.title, 'Sentry: battery low')
  assert.equal(phone.notification.body, battery.detail)
  assert.equal(fcmMessage(body).message.data.detail, undefined)
})
test('battery detail reaches the outgoing FCM request through the handler', async () => {
  const battery = { ...body, kind: 'battery', detail: 'Car battery is low (12.0 V). The device and Sentry turn off at 11.8 V.' }
  const sent = []
  const response = await handle(request(battery), environment(), async (url, init) => { sent.push(JSON.parse(init.body)); return Response.json({}) }, async () => 'oauth')
  assert.equal(response.status, 200)
  assert.equal(sent[0].message.data.kind, 'battery')
  assert.equal(sent[0].message.data.detail, battery.detail)
})
