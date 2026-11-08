import { randomBytes, createHash } from 'node:crypto'

const [command, installationId] = process.argv.slice(2)
const base = process.env.RELAY_URL
const admin = process.env.ENROLLMENT_ADMIN_SECRET
if (!base || !/^https:\/\/[^/?#@]+\/?$/.test(base) || !/^[a-f0-9]{64}$/.test(admin || '') ||
    !['invite', 'revoke', 'check'].includes(command) || (command === 'revoke' && !/^[a-f0-9]{32}$/.test(installationId || ''))) {
  console.error('Set RELAY_URL and ENROLLMENT_ADMIN_SECRET, then run: node operator.mjs check | invite | revoke <installation-id>')
  process.exit(1)
}
const hex = bytes => randomBytes(bytes).toString('hex')
const call = (path, method, body, auth = admin) => fetch(base.replace(/\/$/, '') + '/v2/' + path, {
  method, redirect: 'error', signal: AbortSignal.timeout(15000),
  headers: { Authorization: 'Bearer ' + auth, 'Content-Type': 'application/json' }, ...(body ? { body: JSON.stringify(body) } : {}),
})

async function check() {
  const invitation = hex(32), id = hex(16), secret = hex(32)
  const step = async (label, response, ok) => {
    const status = (await response).status
    console.log(`${ok.includes(status) ? 'PASS' : 'FAIL'} ${label} (HTTP ${status})`)
    if (!ok.includes(status)) throw Error(label)
    return status
  }
  await step('Worker reachable and admin secret accepted', call('admin/invitations', 'POST', { invitation }), [201])
  await step('Enrollment storage works', call('enroll', 'POST', { invitation, installationId: id,
    secretDigest: createHash('sha256').update(secret).digest('hex'), requestId: hex(16) }), [201])
  const token = process.env.RELAY_TEST_TOKEN || 'galaxy-relay-check'
  const destination = { token, appId: 'com.starpilot.galaxy' }
  try {
    await step('Phone destination accepted', call('subscriptions/' + hex(6), 'PUT', destination, `${id}.${secret}`), [200])
    const sent = await step(process.env.RELAY_TEST_TOKEN ? 'Firebase delivered to your phone' : 'Firebase credentials work (fake phone rejected as expected)',
      call('sentry', 'POST', { ...destination, kind: 'test', eventId: hex(16), wallTimeNs: Date.now() * 1e6, label: 'Relay check' }, `${id}.${secret}`),
      process.env.RELAY_TEST_TOKEN ? [200] : [410])
    if (sent === 200) console.log('Look for a "Galaxy test alert" notification on the phone.')
  } finally {
    await step('Test installation removed', call('admin/installations/' + id, 'DELETE'), [200])
  }
  console.log('Relay check passed.')
}

try {
  if (command === 'check') await check()
  else if (command === 'invite') {
    const invitation = hex(32)
    const response = await call('admin/invitations', 'POST', { invitation })
    if (!response.ok) throw Error(String(response.status))
    console.log('Single-use code (expires in 15 minutes):\n' + invitation)
  } else {
    const response = await call('admin/installations/' + installationId, 'DELETE')
    if (!response.ok) throw Error(String(response.status))
    console.log('Installation revoked.')
  }
} catch { console.error('Operator request failed. Check configuration and relay availability.'); process.exitCode = 1 }
