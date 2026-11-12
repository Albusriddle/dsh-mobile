/**
 * Approval probe that creates a fresh session first.
 *
 * A session keeps the permission mode it was created under, so the older
 * scratch session never asks. Creating one now (while approvals are on) makes
 * the tool call raise `approval.request`.
 *
 *   node D:\DSHtempWS\approve-probe3.mjs
 */
import { generateKeyPairSync, sign } from 'node:crypto'

const HOST = 'http://127.0.0.1:3080'
const b64url = (b) => Buffer.from(b).toString('base64url')

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
    return p.resolve(frame)
  }
  const kind = frame.t === 'ev' ? `ev/${frame.m}` : frame.t
  counts[kind] = (counts[kind] ?? 0) + 1
  if (kind === 'approval.request' || (frame.t === 'ev' && frame.m === 'approval.request')) {
    approvals++
    const id = frame.p?.id ?? frame.id
    console.log('APPROVAL >>>', JSON.stringify(frame).slice(0, 320))
    try {
      const r = await send('approval.resolve', { id, decision: 'allowed-once' })
      console.log('resolved >>>', JSON.stringify(r).slice(0, 120))
    } catch (error) {
      console.log('resolve error:', error.message)
    }
  } else if (counts[kind] <= 2) {
    console.log('evt', kind, JSON.stringify(frame).slice(0, 140))
  }
})

ws.addEventListener('open', async () => {
  try {
    await send('hello', { proto: 1, minor: 0, client: 'approve-probe/0.3', cap: ['approvals.v1', 'agents.v1', 'delta.v1'] })
    const begin = await send('pair.begin', { token })
    const { publicKey, privateKey } = generateKeyPairSync('ec', { namedCurve: 'P-256' })
    const signature = sign('sha256', Buffer.from(begin.r.nonce, 'base64url'), {
      key: privateKey, dsaEncoding: 'ieee-p1363',
    })
    await send('pair.finish', {
      devicePubKey: b64url(publicKey.export({ type: 'spki', format: 'der' })),
      signature: b64url(signature),
    })

    const created = await send('session.create', { request: { preset: 'standard', cwd: 'D:/DSHtempWS' } })
    const sessionId = created.r?.sessionId
    console.log('new session:', sessionId)
    if (!sessionId) throw new Error('session.create returned no id')

    await send('session.prompt', {
      sessionId,
      text: '请用 win_bash 执行 echo approval-probe，把输出原样告诉我。',
      requestId: `approve3-${Date.now()}`,
    })
    for (const wait of [10000, 15000, 20000]) {
      await new Promise((r) => setTimeout(r, wait))
      console.log(`--- +${wait}ms`, JSON.stringify(counts), '---')
    }
    console.log('SUMMARY', JSON.stringify(counts), 'approvals:', approvals)
  } catch (error) {
    console.error('probe failed:', error.message)
  } finally {
    ws.close()
    process.exit(0)
  }
})
