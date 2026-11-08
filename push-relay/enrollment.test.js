import test from 'node:test'
import assert from 'node:assert/strict'
import { EnrollmentRegistry, digest } from './enrollment.js'

function setup(overrides = {}) {
  const records = new Map(); let tail = Promise.resolve(); let sends = 0
  const storage = {
    get: async key => structuredClone(records.get(key)),
    put: async (key, value) => records.set(key, structuredClone(value)),
    delete: async key => records.delete(key),
    transaction(fn) { const next = tail.then(() => fn(storage)); tail = next.catch(() => {}); return next },
  }
  const admin = 'a'.repeat(64), invitation = 'b'.repeat(64), secret = 'c'.repeat(64), id = 'd'.repeat(32)
  const registry = new EnrollmentRegistry({ storage }, { ENROLLMENT_ADMIN_SECRET: admin, SEND: async () => { sends++; return Response.json({ ok: true }) }, ...overrides })
  const request = (path, method = 'GET', data, credential = `${id}.${secret}`) => registry.fetch(new Request(`https://test/v2/${path}`, {
    method, headers: { Authorization: `Bearer ${credential}` }, ...(data ? { body: JSON.stringify(data) } : {}),
  }))
  const enroll = async () => {
    assert.equal((await request('admin/invitations', 'POST', { invitation }, admin)).status, 201)
    const data = { invitation, installationId: id, secretDigest: await digest(secret), requestId: 'e'.repeat(32) }
    assert.equal((await request('enroll', 'POST', data)).status, 201)
    return data
  }
  return { records, request, enroll, id, secret, sends: () => sends }
}

test('invitation enrollment recovers lost response and rejects reuse and forged credentials', async () => {
  const s = setup(), data = await s.enroll()
  assert.equal((await s.request('enroll', 'POST', data)).status, 200)
  assert.equal((await s.request('enroll', 'POST', { ...data, installationId: 'f'.repeat(32) })).status, 403)
  assert.equal((await s.request('installation')).status, 200)
  assert.equal((await s.request('installation', 'GET', null, `${s.id}.${'f'.repeat(64)}`)).status, 401)
  assert.equal((await s.request('installation', 'DELETE')).status, 200)
  assert.equal((await s.request('enroll', 'POST', data)).status, 403)
  assert.equal((await s.request('installation')).status, 401)
})

test('destination ownership, deduplication and removal gate provider calls', async () => {
  const s = setup(); await s.enroll()
  const event = { token: 'fcm-token', appId: 'com.starpilot.galaxy', kind: 'test', eventId: 'abc', wallTimeNs: Date.now() * 1e6, label: '' }
  assert.equal((await s.request('sentry', 'POST', event)).status, 409)
  assert.equal((await s.request('subscriptions/123456789abc', 'PUT', { token: event.token, appId: event.appId })).status, 200)
  assert.equal((await s.request('sentry', 'POST', event)).status, 200)
  assert.equal((await s.request('sentry', 'POST', event)).status, 200)
  assert.equal(s.sends(), 1)
  assert.equal((await s.request('subscriptions/123456789abc', 'DELETE')).status, 200)
  assert.equal((await s.request('sentry', 'POST', { ...event, eventId: 'def' })).status, 409)
  assert.equal(s.sends(), 1)
})

test('rotation recovers retries and confirmation retires the old credential', async () => {
  const s = setup(); await s.enroll()
  const replacement = 'f'.repeat(64), data = { secretDigest: await digest(replacement), requestId: '1'.repeat(32) }
  assert.equal((await s.request('credentials/rotate', 'POST', data)).status, 200)
  assert.equal((await s.request('credentials/rotate', 'POST', data)).status, 200)
  assert.equal((await s.request('credentials/confirm', 'POST', {}, `${s.id}.${replacement}`)).status, 200)
  assert.equal((await s.request('installation')).status, 401)
  assert.equal((await s.request('installation', 'GET', null, `${s.id}.${replacement}`)).status, 200)
})

test('expired invitations and oversized enrollment requests fail closed', async () => {
  const s = setup()
  s.records.set(`invite:${await digest('b'.repeat(64))}`, { expires: 1 })
  assert.equal((await s.request('enroll', 'POST', { invitation: 'b'.repeat(64), installationId: s.id, secretDigest: await digest(s.secret), requestId: 'e'.repeat(32) })).status, 403)
  assert.equal((await s.request('enroll', 'POST', { oversized: 'x'.repeat(9000) })).status, 400)
})

test('concurrent invitation redemption authorizes only one installation', async () => {
  const s = setup()
  s.records.set(`invite:${await digest('b'.repeat(64))}`, { expires: Date.now() + 900000 })
  const attempts = await Promise.all(Array.from({ length: 8 }, async (_, i) => s.request('enroll', 'POST', {
    invitation: 'b'.repeat(64), secretDigest: await digest(s.secret), requestId: i.toString(16).repeat(32), installationId: i.toString(16).repeat(32),
  })))
  assert.equal(attempts.filter(response => response.status === 201).length, 1)
  assert.equal(attempts.filter(response => response.status === 403).length, 7)
  assert.equal([...s.records.keys()].filter(key => key.startsWith('device:')).length, 1)
})

test('installation quotas are enforced and unrelated installations cannot send', async () => {
  const s = setup(); await s.enroll()
  const event = { token: 'quota-phone', appId: 'com.starpilot.galaxy', kind: 'test', eventId: 'quota', wallTimeNs: Date.now() * 1e6, label: '' }
  await s.request('subscriptions/123456789abc', 'PUT', { token: event.token, appId: event.appId })
  for (let i = 0; i < 60; i++) assert.equal((await s.request('sentry', 'POST', { ...event, eventId: 'quota' + i })).status, 200)
  assert.equal((await s.request('sentry', 'POST', { ...event, eventId: 'overflow' })).status, 429)
  assert.equal(s.sends(), 60)
  const otherId = '2'.repeat(32), otherSecret = '3'.repeat(64)
  s.records.set(`device:${otherId}`, { digest: await digest(otherSecret), subscriptions: {}, receipts: {}, hour: 0, count: 0 })
  assert.equal((await s.request('sentry', 'POST', event, `${otherId}.${otherSecret}`)).status, 409)
  assert.equal((await s.request('admin/installations/' + s.id, 'DELETE', null, 'a'.repeat(64))).status, 200)
  assert.equal((await s.request('sentry', 'POST', event)).status, 401)
  assert.equal((await s.request('installation', 'GET', null, `${otherId}.${otherSecret}`)).status, 200)
})

test('automatic enrollment needs no invitation and retries cannot reactivate revoked credentials', async () => {
  const s = setup({ AUTO_ENROLL: 'true' })
  const data = { invitation: '', installationId: s.id, secretDigest: await digest(s.secret), requestId: 'e'.repeat(32) }
  assert.equal((await s.request('enroll', 'POST', data)).status, 201)
  assert.equal((await s.request('enroll', 'POST', data)).status, 200)
  assert.equal((await s.request('installation')).status, 200)
  assert.equal((await s.request('installation', 'DELETE')).status, 200)
  assert.equal((await s.request('enroll', 'POST', data)).status, 409)
  assert.equal((await s.request('installation')).status, 401)
})

test('automatic enrollment fails closed and enforces global and IP signup limits', async () => {
  const data = { invitation: '', installationId: 'd'.repeat(32), secretDigest: await digest('c'.repeat(64)), requestId: 'e'.repeat(32) }
  assert.equal((await setup().request('enroll', 'POST', data)).status, 503)
  assert.equal((await setup({ AUTO_ENROLL: 'true', ENROLLMENT_ADMIN_SECRET: '' }).request('enroll', 'POST', data)).status, 503)
  for (const overrides of [{ AUTO_ENROLL_IP_DAILY_LIMIT: '1' }, { AUTO_ENROLL_DAILY_LIMIT: '1' }, { MAX_INSTALLATIONS: '1' }]) {
    const s = setup({ AUTO_ENROLL: 'true', ...overrides })
    assert.equal((await s.request('enroll', 'POST', data)).status, 201)
    assert.equal((await s.request('enroll', 'POST', { ...data, installationId: 'f'.repeat(32) })).status, 429)
    assert.equal((await s.request('enroll', 'POST', data)).status, 200)
  }
})
