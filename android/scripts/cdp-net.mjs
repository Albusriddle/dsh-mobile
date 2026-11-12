/**
 * Capture failing network requests of the DSH WebView via CDP.
 *   node scripts/cdp-net.mjs <ws-url> [reload=true] [seconds=12]
 */
const wsUrl = process.argv[2]
const reload = (process.argv[3] ?? 'true') !== 'false'
const seconds = Number(process.argv[4] ?? 12)
if (!wsUrl) {
  console.error('usage: node cdp-net.mjs ws://127.0.0.1:9222/devtools/page/<id> [reload] [seconds]')
  process.exit(1)
}

const socket = new WebSocket(wsUrl)
let id = 0
const pending = new Map()
const failures = []
const contexts = new Map()
let wsSockets = 0

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
  if (method === 'Network.requestWillBeSent') contexts.set(params.requestId, params.request.url)
  if (method === 'Network.webSocketCreated') { wsSockets++; console.log('WS open ', params.url) }
  if (method === 'Network.webSocketFrameSent') console.log('WS SENT', (params.response?.payloadData ?? '').slice(0, 160))
  if (method === 'Network.webSocketFrameReceived') console.log('WS RECV', (params.response?.payloadData ?? '').slice(0, 160))
  if (method === 'Network.webSocketFrameError' || method === 'Network.webSocketClosed') {
    console.log('WS event', method, params.url ?? contexts.get(params.requestId) ?? '', params.errorMessage ?? '')
  }
  if (method === 'Network.loadingFailed') {
    failures.push({ url: contexts.get(params.requestId) ?? '?', error: params.errorText, canceled: params.canceled })
  }
  if (method === 'Network.responseReceived' && params.response.status >= 400) {
    failures.push({ url: params.response.url, error: `HTTP ${params.response.status}` })
  }
})

socket.addEventListener('open', async () => {
  try {
    await send('Network.enable')
    await send('Runtime.enable')
    if (reload) await send('Page.reload', { ignoreCache: false })
    await new Promise((r) => setTimeout(r, seconds * 1000))
    console.log(`\n=== failures (${failures.length}) ===`)
    const seen = new Set()
    for (const f of failures) {
      const key = `${f.url} ${f.error}`
      if (seen.has(key)) continue
      seen.add(key)
      console.log(`- ${f.error}  ${f.url.slice(0, 160)}`)
    }
    console.log(`\nWS sockets created: ${wsSockets}`)
  } catch (error) {
    console.error('capture failed:', error.message)
  } finally {
    socket.close()
    process.exit(0)
  }
})
socket.addEventListener('error', () => process.exit(1))
