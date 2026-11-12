/**
 * Minimal Chrome DevTools Protocol probe for the DSH Android WebView.
 *   node scripts/cdp-probe.mjs <ws-url> [expression...]
 * Prints the evaluated result for each expression.
 */
const wsUrl = process.argv[2]
const exprs = process.argv.slice(3)
if (!wsUrl) {
  console.error('usage: node cdp-probe.mjs ws://127.0.0.1:9222/devtools/page/<id> "<js>"')
  process.exit(1)
}

const socket = new WebSocket(wsUrl)
let id = 0
const pending = new Map()

const send = (method, params = {}) => new Promise((resolve, reject) => {
  const msgId = ++id
  pending.set(msgId, { resolve, reject })
  socket.send(JSON.stringify({ id: msgId, method, params }))
  setTimeout(() => {
    if (pending.has(msgId)) {
      pending.delete(msgId)
      reject(new Error(`timeout: ${method}`))
    }
  }, 15000)
})

socket.addEventListener('message', (event) => {
  const msg = JSON.parse(event.data)
  if (msg.id && pending.has(msg.id)) {
    const { resolve, reject } = pending.get(msg.id)
    pending.delete(msg.id)
    if (msg.error) reject(new Error(JSON.stringify(msg.error)))
    else resolve(msg.result)
  }
})

socket.addEventListener('open', async () => {
  try {
    await send('Runtime.enable')
    for (const expr of exprs) {
      const result = await send('Runtime.evaluate', {
        expression: expr,
        awaitPromise: true,
        returnByValue: true,
        userGesture: true,
      })
      const value = result?.result?.value
      const desc = result?.result?.description ?? result?.exceptionDetails?.text ?? ''
      console.log(`\n### ${expr}\n${JSON.stringify(value ?? desc, null, 2)}`)
    }
  } catch (error) {
    console.error('probe failed:', error.message)
  } finally {
    socket.close()
    process.exit(0)
  }
})

socket.addEventListener('error', (e) => {
  console.error('ws error', e.message ?? e)
  process.exit(1)
})
