import test from 'node:test'
import assert from 'node:assert/strict'
import { apnsAuthorization, sendAPNs } from './apns.js'
import { handle } from './relay.js'
const body = { token: `apns.production.${'ab'.repeat(32)}`, appId: 'link.galaxy.bluetooth.natehuby76', kind: 'alarm', eventId: 'abc123', wallTimeNs: Date.now() * 1e6, label: 'iPhone' }
const authorized = async () => 'jwt'

test('APNs sends a visible alert with bounded expiry, correct topic and environment', async () => {
  for (const [environment, host] of [['production', 'api.push.apple.com'], ['sandbox', 'api.sandbox.push.apple.com']]) {
    const response = await sendAPNs({ ...body, token: `apns.${environment}.${'ab'.repeat(32)}` }, {}, async (url, request) => {
      assert.equal(url, `https://${host}/3/device/${'ab'.repeat(32)}`)
      assert.equal(request.headers['apns-topic'], body.appId)
      assert.equal(request.headers['apns-push-type'], 'alert')
      assert.equal(request.headers['apns-collapse-id'], body.eventId)
      assert.equal(request.redirect, 'manual')
      const payload = JSON.parse(request.body)
      assert.equal(payload.aps.alert.title, 'Sentry alarm')
      assert.equal(payload.type, 'sentry')
      assert.equal(payload.eventId, body.eventId)
      assert.equal(payload.token, undefined)
      assert.ok(Number(request.headers['apns-expiration']) <= Date.now() / 1000 + 601)
      return new Response(null, { status: 200 })
    }, authorized)
    assert.equal(response.status, 200)
  }
})

test('only unregistered Apple tokens remove subscriptions; setup errors remain retryable', async () => {
  for (const [status, reason, expected] of [[410, 'Unregistered', 410], [400, 'BadDeviceToken', 502], [400, 'DeviceTokenNotForTopic', 502], [403, 'InvalidProviderToken', 502], [429, 'TooManyRequests', 429], [500, 'InternalServerError', 502]]) {
    const response = await sendAPNs(body, {}, async () => Response.json({ reason }, { status }), authorized)
    assert.equal(response.status, expected)
  }
  assert.equal((await sendAPNs({ ...body, token: 'apns.production.invalid' }, {}, () => assert.fail('must not send'), authorized)).status, 400)
})

test('ES256 authorization signs a verifiable JWT and refreshes when credentials change', async () => {
  const key = await crypto.subtle.generateKey({ name: 'ECDSA', namedCurve: 'P-256' }, true, ['sign', 'verify'])
  const raw = Buffer.from(await crypto.subtle.exportKey('pkcs8', key.privateKey)).toString('base64')
  const env = { APNS_PRIVATE_KEY: `-----BEGIN PRIVATE KEY-----\n${raw}\n-----END PRIVATE KEY-----`, APNS_TEAM_ID: 'ABCDEFGHIJ', APNS_KEY_ID: '1234567890' }
  const jwt = await apnsAuthorization(env)
  const [header, payload, signature] = jwt.split('.')
  assert.equal(JSON.parse(Buffer.from(header, 'base64url')).alg, 'ES256')
  assert.equal(JSON.parse(Buffer.from(payload, 'base64url')).iss, env.APNS_TEAM_ID)
  assert.equal(await crypto.subtle.verify({ name: 'ECDSA', hash: 'SHA-256' }, key.publicKey, Buffer.from(signature, 'base64url'), new TextEncoder().encode(`${header}.${payload}`)), true)
  assert.equal(await apnsAuthorization(env), jwt)
  const changed = await apnsAuthorization({ ...env, APNS_KEY_ID: 'ABCDEFGHIJ' })
  assert.notEqual(changed, jwt)
  let count = 0
  const response = await handle(new Request('https://relay.test/v1/sentry', { method: 'POST', body: JSON.stringify(body) }), {
    ...env, RATE: { get: async () => 0, put: async () => {} },
  }, async url => { assert.ok(url.startsWith('https://api.push.apple.com/')); count++; return new Response(null, { status: 200 }) }, () => assert.fail('APNs must not require Firebase'))
  assert.equal(response.status, 200)
  assert.equal(count, 1)
})
