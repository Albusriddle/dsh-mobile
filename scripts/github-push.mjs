#!/usr/bin/env node
/**
 * Push a directory to a GitHub repository through the REST API.
 *
 * Why: this machine cannot reach github.com:443 (git push times out), while
 * api.github.com is reachable. The API can still build a commit
 * (blobs → tree → commit → ref), which is all `git push` would do.
 *
 *   node scripts/github-push.mjs <dir> <owner/repo> <branch> <message>
 *
 * Token: GITHUB_TOKEN, or read from the local git credential store.
 */
import { readdirSync, readFileSync, statSync } from 'node:fs'
import { execFileSync } from 'node:child_process'
import { join, relative, sep } from 'node:path'

const [dir, slug, branch = 'main', message = 'update'] = process.argv.slice(2)
if (!dir || !slug) {
  console.error('usage: node scripts/github-push.mjs <dir> <owner/repo> [branch] [message]')
  process.exit(1)
}

const token = process.env.GITHUB_TOKEN ?? (() => {
  const out = execFileSync('git', ['credential', 'fill'], { input: 'protocol=https\nhost=github.com\n\n' })
  const line = String(out).split(/\r?\n/).find((l) => l.startsWith('password='))
  return line ? line.slice('password='.length) : ''
})()
if (!token) {
  console.error('no token')
  process.exit(1)
}

const api = (path, init = {}) => fetch(`https://api.github.com${path}`, {
  ...init,
  headers: {
    authorization: `token ${token}`,
    accept: 'application/vnd.github+json',
    'content-type': 'application/json',
    'user-agent': 'dsh-mobile-push',
    ...(init.headers ?? {}),
  },
})
const jsonOrThrow = async (res, what) => {
  if (!res.ok) throw new Error(`${what}: HTTP ${res.status} ${await res.text()}`)
  return res.json()
}

const skip = new Set(['.git', 'node_modules', 'build', '.gradle'])
function walk(base, out = []) {
  for (const name of readdirSync(base)) {
    if (skip.has(name)) continue
    const full = join(base, name)
    const stat = statSync(full)
    if (stat.isDirectory()) walk(full, out)
    else out.push(full)
  }
  return out
}

const files = walk(dir).filter((p) => !p.endsWith('.apk') && !p.endsWith('.aab'))
console.log(`uploading ${files.length} files to ${slug}@${branch}`)

const entries = []
for (const file of files) {
  const content = readFileSync(file).toString('base64')
  const blob = await jsonOrThrow(await api(`/repos/${slug}/git/blobs`, {
    method: 'POST',
    body: JSON.stringify({ content, encoding: 'base64' }),
  }), `blob ${file}`)
  entries.push({
    path: relative(dir, file).split(sep).join('/'),
    mode: '100644',
    type: 'blob',
    sha: blob.sha,
  })
}

const tree = await jsonOrThrow(await api(`/repos/${slug}/git/trees`, {
  method: 'POST',
  body: JSON.stringify({ tree: entries }),
}), 'tree')

// Keep history when the branch already exists.
let parent
const ref = await api(`/repos/${slug}/git/ref/heads/${branch}`)
if (ref.ok) parent = (await ref.json()).object.sha

const commit = await jsonOrThrow(await api(`/repos/${slug}/git/commits`, {
  method: 'POST',
  body: JSON.stringify({ message, tree: tree.sha, ...(parent ? { parents: [parent] } : {}) }),
}), 'commit')

if (parent) {
  await jsonOrThrow(await api(`/repos/${slug}/git/refs/heads/${branch}`, {
    method: 'PATCH',
    body: JSON.stringify({ sha: commit.sha, force: false }),
  }), 'ref update')
} else {
  await jsonOrThrow(await api(`/repos/${slug}/git/refs`, {
    method: 'POST',
    body: JSON.stringify({ ref: `refs/heads/${branch}`, sha: commit.sha }),
  }), 'ref create')
}
console.log(`pushed commit ${commit.sha.slice(0, 10)} → https://github.com/${slug}/tree/${branch}`)
