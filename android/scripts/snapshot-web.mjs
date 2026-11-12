#!/usr/bin/env node
/**
 * DSH web snapshotter.
 *
 * Captures the static shell of the running DSH host (loopback, no auth for
 * assets) into the Android app's assets so the APK can answer those requests
 * locally while /api still goes to the real origin.
 *
 *   node scripts/snapshot-web.mjs [--host http://127.0.0.1:3080] [--out <dir>]
 *
 * Output: <out>/files/<sha1>.<ext>  +  <out>/asset-index.json
 */
import { createHash } from 'node:crypto'
import { mkdir, writeFile, rm } from 'node:fs/promises'
import { join, extname } from 'node:path'

const args = process.argv.slice(2)
const argOf = (name, fallback) => {
  const i = args.indexOf(name)
  return i === -1 ? fallback : args[i + 1]
}
const HOST = argOf('--host', 'http://127.0.0.1:3080')
const OUT = argOf('--out', 'D:/DSHtempWS/dsh-android/app/src/main/assets/web')
const FILES = join(OUT, 'files')

const MIME = {
  '.js': 'application/javascript; charset=utf-8',
  '.mjs': 'application/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.webmanifest': 'application/manifest+json; charset=utf-8',
  '.png': 'image/png',
  '.svg': 'image/svg+xml',
  '.ico': 'image/x-icon',
  '.woff2': 'font/woff2',
  '.html': 'text/html; charset=utf-8',
}

const sha1 = (buf) => createHash('sha1').update(buf).digest('hex')

/** Mint a pairing token and open the cookieless app landing to get the shell HTML. */
async function shellHtml() {
  const issue = await fetch(`${HOST}/api/pair/issue`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: '{}',
  })
  if (!issue.ok) throw new Error(`issue failed: ${issue.status}`)
  const { token } = await issue.json()
  // Follow the accept redirect chain; keep the landing HTML.
  let url = `${HOST}/pair-accept?pair=${token}`
  for (let hop = 0; hop < 5; hop += 1) {
    const res = await fetch(url, { redirect: 'manual' })
    const location = res.headers.get('location')
    if (res.status >= 300 && res.status < 400 && location) {
      url = new URL(location, url).toString()
      continue
    }
    if (!res.ok) throw new Error(`shell fetch failed: ${res.status} ${url}`)
    return { html: await res.text(), token, url }
  }
  throw new Error('too many redirects')
}

/** Collect every static URL the shell references. */
function collectUrls(html) {
  const urls = new Set()
  // Relative asset references: ./assets/x.js, assets/x.css, ./manifest.webmanifest, icons...
  for (const m of html.matchAll(/(?:src|href)="([^"]+)"/g)) urls.add(m[1])
  // Boot payload may carry the same paths with escaped ampersands.
  for (const m of html.matchAll(/plugins\/\?\?[^"'\s<]+/g)) urls.add(m[0])
  return [...new Set([...urls]
    .map((u) => u.replaceAll('&amp;', '&'))
    .filter((u) => !u.startsWith('data:') && !u.startsWith('http'))
    .map((u) => (u.startsWith('/') ? u : `/${u.replace(/^\.\//, '')}`))
    // API and the shell itself are never bundled: they must hit the host.
    .filter((u) => !u.startsWith('/api/') && u !== '/' && u !== '/pair-app'))]
}

const extOf = (path) => {
  const clean = path.split('?')[0]
  const ext = extname(clean).toLowerCase()
  if (ext) return ext
  if (clean.includes('/client.js') || clean.endsWith('.js')) return '.js'
  return '.bin'
}

async function main() {
  await rm(OUT, { recursive: true, force: true })
  await mkdir(FILES, { recursive: true })

  const { html, token, url } = await shellHtml()
  const urls = collectUrls(html)
  console.log(`shell ${url} (${html.length} bytes), ${urls.length} static refs`)

  const entries = []
  let bytes = 0
  for (const path of urls) {
    const res = await fetch(`${HOST}${path}`)
    if (!res.ok) {
      console.warn(`  skip ${path} -> ${res.status}`)
      continue
    }
    const buf = Buffer.from(await res.arrayBuffer())
    const ext = extOf(path)
    const digest = createHash('sha256').update(buf).digest('hex')
    const already = entries.find((e) => e.sha256 === digest)
    if (already) {
      entries.push({ ...already, url: path })
      continue
    }
    const file = `${digest.slice(0, 20)}${ext}`
    await writeFile(join(FILES, file), buf)
    entries.push({
      url: path,
      file,
      type: MIME[ext] ?? res.headers.get('content-type') ?? 'application/octet-stream',
      bytes: buf.length,
      sha256: createHash('sha256').update(buf).digest('hex'),
    })
    bytes += buf.length
  }

  await writeFile(join(OUT, 'shell.html'), html, 'utf8')
  await writeFile(
    join(OUT, 'asset-index.json'),
    JSON.stringify(
      {
        capturedAt: new Date().toISOString(),
        host: HOST,
        shellBytes: Buffer.byteLength(html),
        totalBytes: bytes,
        entries,
      },
      null,
      2,
    ),
    'utf8',
  )
  console.log(`captured ${entries.length} files, ${(bytes / 1024 / 1024).toFixed(2)} MB -> ${OUT}`)
  console.log(`pair token used: ${token.slice(0, 8)}… (devices created by this run should be revoked)`)
}

main().catch((error) => {
  console.error(error)
  process.exit(1)
})
