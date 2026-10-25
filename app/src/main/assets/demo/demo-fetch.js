(() => {
  const original = window.fetch
  window.fetch = async (input, options = {}) => {
    const url = new URL(typeof input === 'string' ? input : input.url, location.href)
    if (url.origin !== location.origin) return new Response('{"error":"Offline demo only"}', {status:403})
    if (!url.pathname.startsWith('/api/')) return original(input, options)
    const reply = JSON.parse(window.StarPilotApp.demoRequest(options.method || 'GET', url.pathname, options.body || '{}'))
    return new Response(reply.body, {status: reply.status, headers: {'Content-Type':'application/json'}})
  }
  window.WebSocket = class { constructor() { throw Error('Offline demo') } }
  window.EventSource = class { constructor() { throw Error('Offline demo') } }
})()
