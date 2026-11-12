/**
 * Dispatch a real touch tap at the centre of an element found by a JS expression,
 * then report the page state. Used to test whether the phone shell reacts to a
 * finger tap (not a synthetic .click()).
 *   node scripts/cdp-tap.mjs <ws-url> "<js-expr-returning-element>"
 */
const wsUrl = process.argv[2]
const expr = process.argv[3] ?? "document.querySelector('[aria-label=\"打开侧边栏\"]')"
if (!wsUrl) {
  console.error('usage: node cdp-tap.mjs <ws-url> "<js expr>"')
  process.exit(1)
}

const socket = new WebSocket(wsUrl)
let id = 0
const pending = new Map()
const send = (method, params = {}) => new Promise((resolve, reject) => {
  const msgId = ++id
  pending.set(msgId, { resolve, reject })
  socket.send(JSON.stringify({ id: msgId, method, params }))
  setTimeout(() => { if (pending.has(msgId)) { pending.delete(msgId); reject(new Error('timeout ' + method)) } }, 10000)
})
socket.addEventListener('message', (event) => {
  const msg = JSON.parse(event.data)
  if (msg.id && pending.has(msg.id)) {
    const p = pending.get(msg.id)
    pending.delete(msg.id)
    msg.error ? p.reject(new Error(JSON.stringify(msg.error))) : p.resolve(msg.result)
  }
})
const evaluate = async (source) => {
  const r = await send('Runtime.evaluate', { expression: source, returnByValue: true, awaitPromise: true })
  return r?.result?.value
}
socket.addEventListener('open', async () => {
  try {
    await send('Runtime.enable')
    const rect = await evaluate(`(() => { const el = ${expr}; if (!el) return null; const r = el.getBoundingClientRect(); return { x: r.x + r.width / 2, y: r.y + r.height / 2, w: r.width, h: r.height, text: (el.textContent || '').trim().slice(0, 30) } })()`)
    if (!rect) { console.log('element not found'); process.exit(0) }
    console.log('tap target:', JSON.stringify(rect))
    const point = { x: Math.round(rect.x), y: Math.round(rect.y) }
    await send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ ...point, id: 1 }] })
    await new Promise((r) => setTimeout(r, 60))
    await send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] })
    await new Promise((r) => setTimeout(r, 2500))
    console.log('after href:', await evaluate('location.href'))
    console.log('after text:', String(await evaluate("document.body.innerText.replace(/\\n+/g,' | ').slice(0,160)")).slice(0, 200))
  } catch (error) {
    console.error('tap failed:', error.message)
  } finally {
    socket.close()
    process.exit(0)
  }
})
socket.addEventListener('error', () => process.exit(1))
