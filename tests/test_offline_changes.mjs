import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import vm from 'node:vm'
import test from 'node:test'

const source = readFileSync(new URL('../app/src/main/assets/offline-changes.js', import.meta.url), 'utf8')
const row = (label, value, extra = {}) => ({ label, value, choices: [], step: 0, minimum: 0, maximum: 0, unit: '', available: true, action: true,
  confirm: false, repairValue: '', page: '', heading: false, defaultValue: null, resetAvailable: false, reason: 'Help.', ...extra })
const rows = () => [
  row('Switch', 'Off', { choices: ['Off', 'On'], defaultValue: 'On', resetAvailable: true }),
  row('Follow', '1.25', { step: 0.05, minimum: 0.5, maximum: 3, unit: 's' }),
  row('Volume', 'Auto', { choices: ['Auto', '25%', '30%'], step: 5, minimum: 25, maximum: 100, unit: '%' }),
  row('Twin', 'A', { choices: ['A', 'B'] }),
  row('Twin', 'A', { choices: ['A', 'B'] }),
  row('Reset calibration', '', { confirm: true }),
  row('More', '', { page: 'other', action: false }),
  row('Strength', '100', { step: 1, minimum: 20, maximum: 200, unit: '%', displayScale: 'lane-strength' }),
]
const base = { Request, Response, URL, Map, Set, Promise, Error, JSON, Math, Number, String, Array, Object, encodeURIComponent, decodeURIComponent,
  location: { href: 'https://galaxy-offline.invalid/', origin: 'https://galaxy-offline.invalid' } }

function offline() {
  let queue = []
  const scope = { ...base, fetch: async () => new Response('asset'),
    StarPilotOffline: {
      page: (name) => name === 'slc' ? JSON.stringify({ page: 'slc', title: 'Speed', parked: false, rows: rows(), view: 'live' }) : '',
      saved: (path) => path === '/api/auth/session' ? '{"authenticated":true}' : '',
      pending: () => JSON.stringify(queue),
      enqueue: (raw) => { const op = JSON.parse(raw); queue = queue.filter((o) => !(o.label === op.label && o.nth === op.nth)); queue.push(op); return true },
      drop: (page, label, nth) => { queue = queue.filter((o) => !(o.label === label && o.nth === nth)) },
    } }
  scope.window = scope
  vm.runInNewContext(source, scope)
  const url = (path) => 'https://galaxy-offline.invalid' + path
  const post = async (path, body) => { const r = await scope.fetch(url(path), { method: 'POST', body: JSON.stringify(body) }); return { status: r.status, data: await r.json() } }
  const page = async () => (await scope.fetch(url('/api/settings/pages/slc'))).json()
  const change = async (body) => { const staged = await post('/api/settings/preview', { view: 'offline:slc', ...body }); return staged.status === 200 ? post('/api/settings/confirm', { intent: staged.data.intent, confirmed: true, page: 'slc' }) : staged }
  return { scope, url, post, page, change, queue: () => queue }
}

test('offline pages come from the saved copy and only settings requests are answered', async () => {
  const { scope, url, page } = offline()
  const data = await page()
  assert.equal(data.view, 'offline:slc')
  assert.equal(data.parked, true)
  assert.equal(data.rows[5].available, false) // confirm-only actions need a connection
  assert.match(data.rows[5].reason, /Connect to StarPilot/)
  assert.equal(data.rows[6].page, 'other')
  assert.equal((await scope.fetch(url('/api/settings/pages/missing'))).status, 404)
  assert.equal((await scope.fetch(url('/api/auth/session'))).status, 200)
  assert.equal((await scope.fetch(url('/api/drives/stats'))).status, 503)
  assert.equal((await scope.fetch(url('/api/device/reboot'), { method: 'POST', body: '{}' })).status, 503)
  assert.equal((await scope.fetch('https://evil.test/api/settings/pages/slc')).status, 503)
  assert.equal(await (await scope.fetch(url('/js/app.js'))).text(), 'asset')
  assert.throws(() => new scope.WebSocket('wss://x'))
})

test('changes are queued, shown on the page, and replaced by newer ones', async () => {
  const { page, change, post, queue } = offline()
  const saved = await change({ row: 0, value: 'On' })
  assert.equal(saved.data.saved, true)
  assert.equal(saved.data.page.rows[0].value, 'On')
  assert.match(saved.data.page.rows[0].reason, /^Waiting to sync/)
  assert.deepEqual(queue().map((o) => [o.page, o.label, o.nth, o.kind, o.value]), [['slc', 'Switch', 0, 'value', 'On']])
  // Stepping uses comma's own rules: numeric steps clamp, choices wrap.
  await change({ row: 1, direction: 1 })
  assert.equal((await page()).rows[1].value, '1.3')
  await change({ row: 1, direction: 1 })
  assert.deepEqual(queue().filter((o) => o.label === 'Follow').map((o) => [o.value, o.shown]), [[1.35, '1.35']])
  await change({ row: 2, direction: -1 })
  assert.equal(queue().at(-1).value, '30%')
  // Rows sharing a label cannot be addressed safely later, so they need a connection.
  assert.equal((await change({ row: 4, value: 'B' })).status, 409)
  assert.equal((await page()).rows[4].available, false)
  // Lane strength steps by level: 5 -> 6 is 100% -> 120% for a 200% maximum.
  await change({ row: 7, direction: 1 })
  assert.equal(queue().at(-1).value, 120)
  // Going back to what comma already has forgets the queued change.
  await change({ row: 0, value: 'Off' })
  assert.equal(queue().some((o) => o.label === 'Switch'), false)
  const reset = await post('/api/settings/reset-default', { view: 'offline:slc', row: 0 })
  await post('/api/settings/confirm', { intent: reset.data.intent, confirmed: true })
  assert.deepEqual(queue().filter((o) => o.label === 'Switch').map((o) => [o.kind, o.shown]), [['reset', 'On']])
})

test('changes comma would refuse are refused offline', async () => {
  const { change, post, queue } = offline()
  for (const body of [{ row: 1, value: 9 }, { row: 1, value: 1.26 }, { row: 0, value: 'Maybe' }, { row: 5, direction: 0 }, { row: 6, value: 'x' },
    { row: 99, value: 'On' }, { row: 0, direction: 0 }, { row: 1, direction: 0, draft: {} }]) assert.equal((await change(body)).status, 409, JSON.stringify(body))
  assert.equal((await post('/api/settings/preview', { view: 'live', row: 0, value: 'On' })).status, 409)
  assert.equal((await post('/api/settings/confirm', { intent: 'offline:404', confirmed: true })).status, 409)
  assert.equal((await post('/api/settings/reset-default', { view: 'offline:slc', row: 1 })).status, 409)
  assert.equal(queue().length, 0)
})

// A small stand-in for comma's settings API.
function live(ops, { parked = true, fail = null, crawl = false } = {}) {
  const state = rows(), log = [], results = [], saved = [], finished = []
  let intents = 0
  const pending = new Map()
  const reply = (status, body) => new Response(JSON.stringify(body), { status })
  const scope = { ...base, setTimeout: (run, ms) => { if (ms < 30000) setImmediate(run); return 0 }, // the 30 s driving re-check is not followed
    fetch: async (path, options = {}) => {
      const body = options.body ? JSON.parse(options.body) : null
      log.push((options.method || 'GET') + ' ' + path)
      if (fail?.(path, body)) return fail(path, body)
      if (path === './api/galaxy/status') return reply(200, { url: 'https://galaxy.firestar.link/abc' })
      if (path === './api/galaxy/device-name') return reply(200, { name: 'Car' })
      if (path === './api/settings/pages/slc') return reply(200, { page: 'slc', parked, rows: state, view: 'v' })
      if (path.startsWith('./api/settings/pages/')) return reply(404, {})
      if (path === './api/settings/preview' || path === './api/settings/reset-default') {
        if (!parked) return reply(409, {})
        const id = 'i' + (++intents)
        pending.set(id, [body.row, 'value' in body ? String(body.value) : state[body.row].defaultValue])
        return reply(200, { intent: id })
      }
      if (path === './api/settings/confirm') { const [index, value] = pending.get(body.intent); state[index] = { ...state[index], value }; return reply(200, { saved: true }) }
      return reply(200, { ok: true })
    },
    StarPilotQueue: {
      begin: (device, name) => { log.push('begin ' + device + ' ' + name); return JSON.stringify({ ops, crawl, gap: 0 }) },
      result: (device, id, outcome, message) => results.push([id, outcome, message]),
      snapshot: (device, page) => saved.push(page), saved: (device, path) => saved.push(path),
      finish: (device, complete) => finished.push(complete),
    } }
  scope.window = scope
  vm.runInNewContext(source, scope)
  return { state, log, results, saved, finished, done: async () => { for (let i = 0; i < 200 && !finished.length; i++) await new Promise((r) => setImmediate(r)) } }
}
const op = (id, label, value, extra = {}) => ({ id, page: 'slc', label, nth: 0, kind: 'value', value, shown: String(value), ...extra })

test('a connection applies queued changes through preview and confirm, then saves fresh pages', async () => {
  const run = live([op('1', 'Switch', 'On'), op('2', 'Follow', 1.3), op('3', 'Twin', 'B'), op('4', 'Strength', 100),
    op('5', 'Gone', 'On'), op('6', 'Reset calibration', 'confirm'), op('7', 'Volume', 'Auto', { kind: 'reset' }), op('8', 'Other', 'On', { page: 'missing' })])
  await run.done()
  assert.deepEqual(run.results.map(([id, outcome]) => id + ' ' + outcome),
    ['1 applied', '2 applied', '3 rejected', '4 applied', '5 rejected', '6 rejected', '7 rejected', '8 rejected'])
  assert.deepEqual([run.state[0].value, run.state[1].value, run.state[3].value, run.state[4].value], ['On', '1.3', 'A', 'A'])
  assert.equal(run.log.filter((line) => line.endsWith('settings/confirm')).length, 2) // "Strength = 100" was already true
  assert.equal(run.log[2], 'begin https://galaxy.firestar.link/abc Car')
  assert.ok(run.saved.includes('slc') && run.saved.includes('/api/auth/session')) // applied changes refresh the saved pages
  assert.deepEqual(run.finished, [true])
})

test('while driving, changes wait instead of being discarded', async () => {
  const run = live([op('1', 'Switch', 'On')], { parked: false })
  await run.done()
  assert.deepEqual(run.results, [['1', 'waiting', '']])
  assert.equal(run.state[0].value, 'Off')
  assert.deepEqual(run.finished, [true])
})

test('a dropped connection stops the sync and keeps everything queued', async () => {
  const run = live([op('1', 'Switch', 'On'), op('2', 'Follow', 1.3)], { fail: (path) => path === './api/settings/confirm' ? new Response('', { status: 502 }) : null })
  await run.done()
  assert.deepEqual(run.results, [])
  assert.deepEqual(run.finished, [false])
})

test('a refused value is dropped but a busy comma is retried', async () => {
  const refused = live([op('1', 'Switch', 'On')], { fail: (path) => path === './api/settings/preview' ? new Response('{}', { status: 400 }) : null })
  await refused.done()
  assert.equal(refused.results[0][1], 'rejected')
  const busy = live([op('1', 'Switch', 'On')], { fail: (path) => path === './api/settings/pages/slc' ? new Response('{}', { status: 409 }) : null })
  await busy.done()
  assert.equal(busy.results[0][1], 'retry')
})

test('with nothing queued, pages are saved only when due', async () => {
  const idle = live([])
  await idle.done()
  assert.deepEqual(idle.saved, [])
  const due = live([], { crawl: true })
  await due.done()
  assert.ok(due.saved.includes('slc'))
  assert.deepEqual(due.finished, [true])
})

test('sync waits for Galaxy to finish signing in instead of giving up', async () => {
  let refusals = 2
  const run = live([op('1', 'Switch', 'On')], { fail: (path) => path === './api/galaxy/status' && refusals-- > 0 ? new Response('{}', { status: 401 }) : null })
  await run.done()
  assert.deepEqual(run.results.map(([id, outcome]) => id + ' ' + outcome), ['1 applied'])
})
