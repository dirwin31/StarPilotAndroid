import { randomBytes } from 'node:crypto'

const [command, installationId] = process.argv.slice(2)
const base = process.env.RELAY_URL
const admin = process.env.ENROLLMENT_ADMIN_SECRET
if (!base || !/^https:\/\/[^/?#@]+\/?$/.test(base) || !/^[a-f0-9]{64}$/.test(admin || '') ||
    !['invite', 'revoke'].includes(command) || (command === 'revoke' && !/^[a-f0-9]{32}$/.test(installationId || ''))) {
  console.error('Set RELAY_URL and ENROLLMENT_ADMIN_SECRET, then run: node operator.mjs invite | revoke <installation-id>')
  process.exit(1)
}
const invitation = randomBytes(32).toString('hex')
try {
  const response = await fetch(base.replace(/\/$/, '') + '/v2/admin/' + (command === 'invite' ? 'invitations' : 'installations/' + installationId), {
    method: command === 'invite' ? 'POST' : 'DELETE', redirect: 'error', signal: AbortSignal.timeout(15000),
    headers: { Authorization: 'Bearer ' + admin, 'Content-Type': 'application/json' },
    ...(command === 'invite' ? { body: JSON.stringify({ invitation }) } : {}),
  })
  if (!response.ok) throw Error(String(response.status))
  if (command === 'invite') console.log('Single-use code (expires in 15 minutes):\n' + invitation)
  else console.log('Installation revoked.')
} catch { console.error('Operator request failed. Check configuration and relay availability.'); process.exitCode = 1 }
