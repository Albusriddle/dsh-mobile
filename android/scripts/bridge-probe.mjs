#!/usr/bin/env node
/**
 * DSH remote-bridge protocol v1 probe (P0).
 *
 *   node scripts/bridge-probe.mjs [host]
 *
 * Flow: GET bootstrap token (loopback route) → WS /ios/v1 → hello →
 * pair.begin → ES256 sign nonce → pair.finish → session.list → session.snapshot.
 * Prints every frame verbatim so a protocol drift is obvious.
 */
import { generateKeyPairSync, sign as cryptoSign } from 'node:crypto'

const HOST = process.argv[2] ?? 'http://127.0.0.1:3080'
const b64url = (buf) => Buffer.from(buf).toString('base64url')
const log = (...args) => console.log(...args)

const tokenRes = await fetch(`${HOST}/remote-bridge/pair/token`)
const tokenBody = await tokenRes.json().catch(() => ({}))
log('token route:', tokenRes.status, JSON.stringify(tokenBody).slice(0, 160))
const bootstrap = tokenBody.token ?? tokenBody.value
if (!bootstrap) {
  console.error('no bootstrap token; aborting')
  process.exit(1)
}

const ws = new WebSocket(HOST.replace(/^http/, 'ws') + '/ios/v1')
let nextId = 0
const pending = new Map()
const events = []

const send = (method, payload = {}) => new Promise((resolve, reject) => {
  const id = ++nextId
  pending.set(id, { resolve, reject })
  const frame = { t: 'req', id, m: method, p: payload }
  ws.send(JSON.stringify(frame))
  setTimeout(() => {
    if (pending.has(id)) {
      pending.delete(id)
      reject(new Error(`timeout: ${method}`))
    }
  }, 15000)
})

ws.addEventListener('message', (event) => {
  const frame = JSON.parse(String(event.data))
  if (frame.t === 'res' && pending.has(frame.id)) {
    const p = pending.get(frame.id)
    pending.delete(frame.id)
    frame.ok === false ? p.reject(new Error(JSON.stringify(frame))) : p.resolve(frame)
    return
  }
  events.push(frame)
  if (events.length <= 6) log('event:', JSON.stringify(frame).slice(0, 220))
})

ws.addEventListener('error', (e) => { console.error('ws error', e.message ?? e); process.exit(1) })

ws.addEventListener('open', async () => {
  try {
    const hello = await send('hello', { proto: 1, minor: 0, client: 'node-probe/0.1', cap: [] })
    log('hello:', JSON.stringify(hello.r ?? hello).slice(0, 260))
    const auth = hello.r?.auth ?? 'unknown'

    if (auth !== 'paired') {
      const begin = await send('pair.begin', { token: bootstrap })
      const nonce = begin.r?.nonce
      const expiresAt = begin.r?.expiresAt
      log('pair.begin:', JSON.stringify({ nonce, expiresAt }).slice(0, 200))

      const { publicKey, privateKey } = generateKeyPairSync('ec', { namedCurve: 'P-256' })
      const spki = publicKey.export({ type: 'spki', format: 'der' })
      const rawNonce = Buffer.from(nonce, 'base64url')
      const signature = cryptoSign('sha256', rawNonce, { key: privateKey, dsaEncoding: 'ieee-p1363' })
      const finish = await send('pair.finish', {
        devicePubKey: b64url(spki),
        signature: b64url(signature),
      })
      log('pair.finish:', JSON.stringify(finish.r ?? finish).slice(0, 260))
      log('save deviceToken for the app:', finish.r?.deviceToken?.slice?.(0, 16) ?? '(none)')
    }

    const list = await send('session.list', {})
    const rows = list.r?.sessions ?? list.r ?? []
    log(`session.list: ${Array.isArray(rows) ? rows.length : '?'} sessions`)
    for (const row of (Array.isArray(rows) ? rows.slice(0, 5) : [])) {
      log('  -', JSON.stringify({
        id: row.sessionId ?? row.id,
        title: row.title,
        cwd: row.cwd,
        blank: row.blank,
      }).slice(0, 200))
    }

    const first = Array.isArray(rows) ? (rows.find((r) => r.blank !== true) ?? rows[0]) : undefined
    const sessionId = first?.sessionId ?? first?.id
    if (sessionId) {
      const snap = await send('session.snapshot', { sessionId })
      const turns = snap.r?.turns ?? []
      log(`session.snapshot: ${turns.length} turns, lastSeq=${snap.r?.lastSeq}`)
      log('  first turn keys:', JSON.stringify(Object.keys(turns[0] ?? {})).slice(0, 160))
    }
    log('OK: protocol v1 reachable, pairing + list + snapshot verified')
  } catch (error) {
    console.error('probe failed:', error.message)
  } finally {
    ws.close()
    process.exit(0)
  }
})
