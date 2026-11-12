/**
 * Capture every request whose URL matches a substring, with its response status,
 * plus console errors. Read-only.
 *   node scripts/cdp-trace.mjs <ws-url> <substr> [seconds]
 */
const wsUrl = process.argv[2]
const needle = (process.argv[3] ?? 'session').toLowerCase()
const seconds = Number(process.argv[4] ?? 14)
if (!wsUrl) {
  console.error('usage: node cdp-trace.mjs ws://127.0.0.1:9333/devtools/page/<id> <substr> [seconds]')
  process.exit(1)
}

const socket = new WebSocket(wsUrl)
let id = 0
const pending = new Map()
const urls = new Map()
const rows = []
const errors = []

const send = (method, params = {}) => new Promise((resolve, reject) => {
  const msgId = ++id
  pending.set(msgId, { resolve, reject })
  socket.send(JSON.stringify({ id: msgId, method, params }))
  setTimeout(() => {
    if (pending.has(msgId)) { pending.delete(msgId); reject(new Error(`timeout ${method}`)) }
  }, 10000)
})

socket.addEventListener('message', (event) => {
  const msg = JSON.parse(event.data)
  if (msg.id && pending.has(msg.id)) {
    const p = pending.get(msg.id)
    pending.delete(msg.id)
    msg.error ? p.reject(new Error(JSON.stringify(msg.error))) : p.resolve(msg.result)
    return
  }
  const { method, params } = msg
  if (method === 'Network.requestWillBeSent') urls.set(params.requestId, params.request.url)
  if (method === 'Network.responseReceived') {
    const url = params.response.url
    if (url.toLowerCase().includes(needle)) {
      rows.push(`${String(params.response.status).padEnd(4)} ${url.replace(/^https?:\/\/[^/]+/, '')}`)
    }
  }
  if (method === 'Network.loadingFailed') {
    const url = urls.get(params.requestId) ?? '?'
    if (url.toLowerCase().includes(needle)) {
      rows.push(`FAIL ${params.errorText} ${url.replace(/^https?:\/\/[^/]+/, '')}`)
    }
  }
  if (method === 'Runtime.consoleAPICalled' && params.type === 'error') {
    errors.push(params.args.map((a) => a.value ?? a.description ?? '').join(' ').slice(0, 200))
  }
  if (method === 'Runtime.exceptionThrown') {
    errors.push(String(params.exceptionDetails?.exception?.description ?? params.exceptionDetails?.text ?? '').slice(0, 200))
  }
})

socket.addEventListener('open', async () => {
  try {
    await send('Network.enable')
    await send('Runtime.enable')
    await send('Page.enable')
    await send('Page.reload')
    await new Promise((r) => setTimeout(r, seconds * 1000))
    console.log(`=== ${rows.length} matching requests (contains "${needle}") ===`)
    const seen = new Set()
    for (const row of rows) {
      if (seen.has(row)) continue
      seen.add(row)
      console.log(row)
    }
    console.log(`\n=== console errors (${errors.length}) ===`)
    for (const e of [...new Set(errors)].slice(0, 12)) console.log('-', e)
  } catch (error) {
    console.error('trace failed:', error.message)
  } finally {
    socket.close()
    process.exit(0)
  }
})
socket.addEventListener('error', () => process.exit(1))
