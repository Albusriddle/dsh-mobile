/**
 * Instrument WebSocket creation in the DSH WebView, reload, and surface the
 * close codes through the app's onConsoleMessage logcat channel.
 *   node scripts/wsprobe.mjs <ws-url>
 */
const wsUrl = process.argv[2]
const socket = new WebSocket(wsUrl)
let id = 0
const pending = new Map()
const send = (method, params = {}) => new Promise((resolve, reject) => {
  const msgId = ++id
  pending.set(msgId, { resolve, reject })
  socket.send(JSON.stringify({ id: msgId, method, params }))
  setTimeout(() => { if (pending.has(msgId)) { pending.delete(msgId); reject(new Error('timeout ' + method)) } }, 8000)
})
socket.addEventListener('message', (e) => {
  const m = JSON.parse(e.data)
  if (m.id && pending.has(m.id)) {
    const p = pending.get(m.id)
    pending.delete(m.id)
    m.error ? p.reject(new Error(JSON.stringify(m.error))) : p.resolve(m.result)
  }
})
socket.addEventListener('open', async () => {
  const script = `(()=>{const O=window.WebSocket;window.WebSocket=function(u,p){const w=new O(u,p);
    w.addEventListener('close',e=>console.log('WS_CLOSE '+u+' code='+e.code+' reason='+(e.reason||'')));
    w.addEventListener('error',()=>console.log('WS_ERROR '+u));
    w.addEventListener('open',()=>console.log('WS_OPEN '+u));
    const os=w.send.bind(w);w.send=function(d){try{console.log('WS_TX '+u+' '+String(d).slice(0,100))}catch(e){}return os(d)};
    w.addEventListener('message',e=>console.log('WS_RX '+u+' '+String(e.data).slice(0,100)));return w};
    window.WebSocket.prototype=O.prototype;
    window.WebSocket.CONNECTING=0;window.WebSocket.OPEN=1;window.WebSocket.CLOSING=2;window.WebSocket.CLOSED=3})()`
  try {
    await send('Page.enable')
    await send('Network.enable')
    await send('Page.addScriptToEvaluateOnNewDocument', { source: script })
    await send('Page.reload')
    console.log('reloaded with WS instrumentation')
  } catch (error) {
    console.error('probe failed:', error.message)
  }
  setTimeout(() => { socket.close(); process.exit(0) }, 12000)
})
socket.addEventListener('error', (e) => { console.error('ws error', e.message ?? e); process.exit(1) })
