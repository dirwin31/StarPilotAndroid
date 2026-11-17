// Offline changes: lets settings be edited away from the comma and applied on the next connection.
// One file, two roles, chosen by which native bridge the page was given:
//   StarPilotOffline -> the offline session: serve saved settings pages and queue changes.
//   StarPilotQueue   -> a live session: apply queued changes through Galaxy's own
//                       preview/confirm flow, then save fresh settings pages.
(() => {
  const ROOTS = ['hub', 'aol', 'lane_change', 'torque', 'lane', 'profiles', 'conditional', 'curve', 'slc', 'aggressive',
    'standard', 'relaxed', 'traffic', 'wheel', 'appearance', 'sounds', 'display', 'data', 'developer', 'localization', 'vehicle', 'power', 'tuning']
  // Read-only answers Galaxy needs to start; saved while connected so the offline session can boot.
  const BOOT = ['/api/auth/session', '/api/local-access', '/api/galaxy/device-name']
  const MAX_PAGES = 48

  const number = (value) => typeof value === 'number' ? value : typeof value === 'string' && value.trim() !== '' ? Number(value) : NaN
  const same = (a, b) => String(a) === String(b) || (Number.isFinite(number(a)) && Number.isFinite(number(b)) && Math.abs(number(a) - number(b)) < 1e-7)
  const decimals = (value) => { const text = String(value), dot = text.indexOf('.'); return dot < 0 || text.includes('e') ? 0 : text.length - dot - 1 }
  // comma prints numbers Python-style ("1.0", "1.25"); keep whichever style the saved value used.
  const shown = (row, value) => {
    let text = String(Number(value.toFixed(Math.min(8, Math.max(decimals(row.step), decimals(row.minimum))))))
    if (String(row.value).includes('.') && !text.includes('.')) text += '.0'
    return text
  }
  // comma does not expose setting keys, so a row is addressed by its label and position among equal labels.
  const address = (rows, index) => {
    let nth = 0
    for (let i = 0; i < index; i++) if (!rows[i].heading && rows[i].label === rows[index].label) nth++
    return { label: rows[index].label, nth }
  }
  const locate = (rows, label, nth) => {
    let seen = 0
    for (let i = 0; i < rows.length; i++) if (!rows[i].heading && rows[i].label === label && seen++ === nth) return i
    return -1
  }
  // Rows sharing a label cannot be told apart safely later, so they are only changed while connected.
  const unique = (rows, index) => rows.filter((row) => !row.heading && row.label === rows[index].label).length === 1
  const queueable = (row) => !!row && row.available === true && row.action === true && !row.heading && !row.page && !row.confirm &&
    !row.repairValue && ((Array.isArray(row.choices) && row.choices.length > 0) || row.step > 0)

  // Mirrors comma's own validation so a queued change is one comma will accept later.
  const propose = (row, body) => {
    const choices = Array.isArray(row.choices) ? row.choices : []
    if ('value' in body) {
      const value = body.value
      if (typeof value === 'string' && choices.includes(value)) return { value, shown: value }
      if (row.step > 0 && (!choices.length || choices.includes('Auto'))) {
        const next = number(value)
        if (!Number.isFinite(next) || next < row.minimum || next > row.maximum) return null
        const steps = (next - row.minimum) / row.step
        if (Math.abs(steps - Math.round(steps)) > 1e-7) return null
        return { value: next, shown: shown(row, next) }
      }
      return null
    }
    const direction = body.direction
    if (direction !== 1 && direction !== -1) return null
    if (choices.length && !(choices.length === 1 && choices[0] === 'Auto' && row.step && row.value !== 'Auto')) {
      const index = choices.indexOf(row.value)
      if (index < 0) return null
      const value = choices[(index + direction + choices.length) % choices.length]
      return { value, shown: value }
    }
    if (!(row.step > 0)) return null
    const current = number(row.value)
    if (!Number.isFinite(current)) return null
    let next
    if (row.displayScale === 'lane-strength') {
      if (row.unit !== '%' || !(row.minimum < 100 && 100 < row.maximum) || current < row.minimum || current > row.maximum) return null
      const level = Math.round((current <= 100 ? 1 + (current - row.minimum) * 4 / (100 - row.minimum) : 5 + (current - 100) * 5 / (row.maximum - 100)) * 100) / 100
      const target = Math.max(1, Math.min(10, level + direction))
      next = Math.floor((target <= 5 ? row.minimum + (target - 1) * (100 - row.minimum) / 4 : 100 + (target - 5) * (row.maximum - 100) / 5) + 0.5)
    } else next = Number(Math.max(row.minimum, Math.min(row.maximum, current + row.step * direction)).toFixed(8))
    if (next === current) return null
    return { value: next, shown: shown(row, next) }
  }

  const json = (status, body) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })

  function installOffline(bridge) {
    const original = window.fetch.bind(window)
    const intents = new Map()
    let sequence = 0
    const offline = () => json(503, { error: 'Not available offline. Connect to StarPilot to use this.', code: 'offline' })
    const saved = (name) => { try { const raw = bridge.page(name); return raw ? JSON.parse(raw) : null } catch { return null } }
    const pending = () => { try { return JSON.parse(bridge.pending()) } catch { return [] } }
    const shape = (name) => {
      const data = saved(name)
      if (!data || !Array.isArray(data.rows)) return null
      const queue = pending().filter((op) => op.page === name)
      data.view = 'offline:' + name
      data.parked = true
      data.subtitle = 'Offline. Changes are saved on this phone and applied the next time it connects to StarPilot.'
      data.rows = data.rows.map((row, index) => {
        const { label, nth } = address(data.rows, index)
        const op = queue.find((item) => item.label === label && item.nth === nth)
        if (row.heading || row.page) return row
        if (!queueable(row) || !unique(data.rows, index)) return row.available ? { ...row, available: false, action: false, resetAvailable: false, reason: 'Connect to StarPilot to use this.' } : { ...row, resetAvailable: false }
        return op ? { ...row, value: op.shown, reason: 'Waiting to sync. ' + (row.reason || '') } : row
      })
      return data
    }
    const stage = (body, reset) => {
      const name = typeof body.view === 'string' && body.view.startsWith('offline:') ? body.view.slice(8) : null
      const data = name && saved(name), page = name && shape(name)
      const row = page?.rows[body.row], source = data?.rows[body.row]
      if (!Number.isInteger(body.row) || !queueable(row) || !source) return null
      const choice = reset ? (row.resetAvailable && row.defaultValue != null ? { value: row.defaultValue, shown: String(row.defaultValue) } : null) : propose(row, body)
      if (!choice) return null
      const id = 'offline:' + (++sequence)
      intents.set(id, { page: name, title: data.title || name, ...address(data.rows, body.row), kind: reset ? 'reset' : 'value', ...choice, unchanged: same(choice.shown, source.value) })
      const display = choice.shown + (row.unit && choice.shown !== 'Auto' && !choice.shown.endsWith(row.unit) ? ' ' + row.unit : '')
      return { intent: id, proposed: choice.shown, question: (reset ? `Reset ${row.label} to its default (${display})` : `Set ${row.label} to ${display}`) +
        '? You are offline, so this is saved on your phone and applied the next time it connects to StarPilot.' }
    }
    window.fetch = async (input, options = {}) => {
      const request = new Request(input, options)
      const url = new URL(request.url, location.href)
      if (url.origin !== location.origin) return offline()
      if (!url.pathname.startsWith('/api/')) return original(input, options)
      const path = url.pathname
      if (request.method === 'GET') {
        if (path.startsWith('/api/settings/pages/')) {
          const page = shape(decodeURIComponent(path.slice('/api/settings/pages/'.length)))
          return page ? json(200, page) : json(404, { error: 'This page was not saved for offline use.' })
        }
        let text = ''
        try { text = bridge.saved(path) } catch {}
        return text ? new Response(text, { status: 200, headers: { 'Content-Type': 'application/json' } }) : offline()
      }
      if (request.method !== 'POST' || !['/api/settings/preview', '/api/settings/reset-default', '/api/settings/confirm'].includes(path)) return offline()
      const body = await request.json().catch(() => null)
      if (!body || typeof body !== 'object') return json(400, { error: 'Invalid request' })
      if (path !== '/api/settings/confirm') {
        const staged = stage(body, path === '/api/settings/reset-default')
        return staged ? json(200, staged) : json(409, { error: 'This change needs a connection to StarPilot.' })
      }
      const intent = intents.get(body.intent)
      intents.delete(body.intent)
      if (!intent || body.confirmed !== true) return json(409, { error: 'Confirmation expired' })
      const { unchanged, ...op } = intent
      // Choosing the value comma already has is not a change; just forget any earlier queued one.
      if (unchanged) bridge.drop(op.page, op.label, op.nth)
      else if (bridge.enqueue(JSON.stringify(op)) === false) return json(409, { error: 'Too many changes are waiting.' })
      return json(200, { saved: true, ...(body.page ? { page: shape(body.page) } : {}) })
    }
    window.WebSocket = class { constructor() { throw Error('Offline') } }
    window.EventSource = class { constructor() { throw Error('Offline') } }
  }

  // Outcomes: applied (done), rejected (comma refused it for good), waiting (comma is driving; try again
  // once parked), retry (inconclusive; counts against the attempt limit), lost (connection dropped; stop).
  async function sync(bridge) {
    const call = async (path, body) => {
      const response = await fetch('./api' + path.slice(4), { credentials: 'same-origin', cache: 'no-store',
        ...(body === undefined ? {} : { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) }) })
      const text = await response.text()
      let data = null
      try { data = JSON.parse(text) } catch {}
      return { status: response.status, data, text }
    }
    const down = (reply) => reply.status === 401 || reply.status >= 500
    const apply = async (op, again = true) => {
      const view = await call('/api/settings/pages/' + encodeURIComponent(op.page))
      if (down(view)) return { state: 'lost' }
      if (view.status === 404) return { state: 'rejected', message: 'that settings page is not on this StarPilot' }
      if (view.status !== 200 || !Array.isArray(view.data?.rows)) return { state: 'retry', message: 'settings were busy' }
      const index = locate(view.data.rows, op.label, op.nth), row = view.data.rows[index]
      if (!row || !unique(view.data.rows, index)) return { state: 'rejected', message: 'that setting is no longer offered' }
      if (same(row.value, op.kind === 'reset' ? row.defaultValue : op.shown)) return { state: 'applied' }
      const driving = view.data.parked === false
      if (op.kind === 'reset' && queueable(row) && !row.resetAvailable) return { state: 'rejected', message: 'it has no default to reset to' }
      if (!queueable(row)) return driving ? { state: 'waiting' } : { state: 'rejected', message: 'StarPilot does not allow changing it right now' }
      const preview = op.kind === 'reset' ? await call('/api/settings/reset-default', { view: view.data.view, row: index })
        : await call('/api/settings/preview', { view: view.data.view, row: index, value: op.value })
      if (down(preview)) return { state: 'lost' }
      if (preview.status === 409 || typeof preview.data?.intent !== 'string') {
        if (preview.status === 400) return { state: 'rejected', message: 'StarPilot did not accept that value' }
        if (driving) return { state: 'waiting' }
        return again ? apply(op, false) : { state: 'rejected', message: 'StarPilot no longer accepts that value' }
      }
      const confirm = await call('/api/settings/confirm', { intent: preview.data.intent, confirmed: true })
      if (down(confirm)) return { state: 'lost' }
      if (confirm.status === 200 && confirm.data?.saved === true) return { state: 'applied' }
      if (driving) return { state: 'waiting' }
      return again ? apply(op, false) : { state: 'retry', message: 'StarPilot could not save it' }
    }

    // Galaxy may still be signing in when the page finishes loading, so give it a few chances.
    let status, name
    for (const wait of [0, 1500, 3000, 6000, 12000, 20000]) {
      if (wait) await new Promise((done) => setTimeout(done, wait))
      try { [status, name] = await Promise.all([call('/api/galaxy/status'), call('/api/galaxy/device-name')]) } catch { status = name = null }
      if (status?.status === 200 && name?.status === 200) break
    }
    if (status?.status !== 200 || name?.status !== 200) { window.__galaxyQueueSync = false; return }
    const title = typeof name.data?.name === 'string' ? name.data.name : ''
    const device = (typeof status.data?.url === 'string' && status.data.url) || (title && 'name:' + title)
    if (!device) return
    const plan = JSON.parse(bridge.begin(device, title))
    let applied = 0, waiting = 0
    for (const op of plan.ops) {
      let outcome
      try { outcome = await apply(op) } catch { outcome = { state: 'lost' } }
      if (outcome.state === 'lost') { bridge.finish(device, false); return }
      if (outcome.state === 'applied') applied++
      if (outcome.state === 'waiting') waiting++
      bridge.result(device, op.id, outcome.state, outcome.message || '')
    }
    if (plan.crawl || applied) {
      try {
        for (const path of BOOT) { const reply = await call(path); if (reply.status === 200 && reply.data) bridge.saved(device, path, reply.text) }
        const seen = new Set(), todo = [...ROOTS]
        while (todo.length && seen.size < MAX_PAGES) {
          const page = todo.shift()
          if (seen.has(page)) continue
          seen.add(page)
          const reply = await call('/api/settings/pages/' + encodeURIComponent(page))
          if (down(reply)) throw Error('lost')
          if (reply.status !== 200 || !Array.isArray(reply.data?.rows)) continue
          bridge.snapshot(device, page, reply.text)
          for (const row of reply.data.rows) if (typeof row.page === 'string' && row.page) todo.push(row.page)
          if (plan.gap) await new Promise((done) => setTimeout(done, plan.gap))
        }
      } catch { bridge.finish(device, false); return }
    }
    bridge.finish(device, true)
    // comma refuses most changes while driving; keep trying quietly until it parks.
    if (waiting) setTimeout(() => { if (window.StarPilotQueue === bridge) sync(bridge).catch(() => {}) }, 30000)
  }

  if (window.StarPilotOffline) installOffline(window.StarPilotOffline)
  else if (window.StarPilotQueue && !window.__galaxyQueueSync) {
    window.__galaxyQueueSync = true
    sync(window.StarPilotQueue).catch(() => {})
  }
})()
