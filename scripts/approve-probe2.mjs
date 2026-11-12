/**
 * Verbose approval probe: prints every frame type so a silent hang is visible.
 *
 *   node D:\DSHtempWS\approve-probe2.mjs [sessionId]
 */
import { generateKeyPairSync, sign } from 'node:crypto'

const HOST = 'http://127.0.0.1:3080'
const b64url = (b) => Buffer.from(b).toString('base64url')
const sessionId = process.argv[2] ?? 'session-4349d51a-4039-4c5e-9bf7-3d8662e11b5f'

const token = (await (await fetch(`${HOST}/remote-bridge/pair/token`)).json()).token
const ws = new WebSocket(`${HOST.replace(/^http/, 'ws')}/ios/v1`)
let nextId = 0
const pending = new Map()
const counts = {}
let approvals = 0

const send = (m, p = {}) => new Promise((resolve, reject) => {
  const id = ++nextId
  pending.set(id, { resolve, reject })
  ws.send(JSON.stringify({ t: 'req', id, m, p }))
  setTimeout(() => {
    if (pending.has(id)) { pending.delete(id); reject(new Error(`timeout ${m}`)) }
  }, 20000)
})

ws.addEventListener('message', async (event) => {
  const frame = JSON.parse(event.data)
  if (frame.t === 'res' && pending.has(frame.id)) {
    const p = pending.get(frame.id)
    pending.delete(frame.id)
    console.log('RES', JSON.stringify(frame).slice(0, 200))
    return p.resolve(frame)
  }
  counts[frame.t] = (counts[frame.t] ?? 0) + 1
  if (frame.t === 'approval.request') {
    approvals++
    console.log('APPROVAL >>>', JSON.stringify(frame).slice(0, 300))
    try {
      const r = await send('approval.resolve', { id: frame.id, decision: 'allowed-once' })
      console.log('resolved >>>', JSON.stringify(r).slice(0, 120))
    } catch (error) {
      console.log('resolve error:', error.message)
    }
  } else if (counts[frame.t] <= 3) {
    console.log('evt', frame.t, JSON.stringify(frame).slice(0, 160))
  }
})

ws.addEventListener('error', (e) => console.log('ws error:', e.message ?? e))

ws.addEventListener('open', async () => {
  try {
    await send('hello', { proto: 1, minor: 0, client: 'approve-probe/0.2', cap: ['approvals.v1', 'delta.v1'] })
    const begin = await send('pair.begin', { token })
    const { publicKey, privateKey } = generateKeyPairSync('ec', { namedCurve: 'P-256' })
    const signature = sign('sha256', Buffer.from(begin.r.nonce, 'base64url'), {
      key: privateKey, dsaEncoding: 'ieee-p1363',
    })
    await send('pair.finish', {
      devicePubKey: b64url(publicKey.export({ type: 'spki', format: 'der' })),
      signature: b64url(signature),
    })
    console.log('--- paired, prompting ---')
    await send('session.prompt', {
      sessionId,
      text: '请用 win_bash 执行 echo approval-probe，把输出原样告诉我。',
      requestId: `approve2-${Date.now()}`,
    })
    for (const wait of [10000, 15000, 20000]) {
      await new Promise((r) => setTimeout(r, wait))
      console.log(`--- +${wait}ms: frames so far`, JSON.stringify(counts), '---')
    }
    console.log('SUMMARY', JSON.stringify(counts), 'approvals:', approvals)
  } catch (error) {
    console.error('probe failed:', error.message)
  } finally {
    ws.close()
    process.exit(0)
  }
})
