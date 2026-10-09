#!/usr/bin/env node
//
// admin-fixture-server.mjs — serves a built events-admin dist/ and answers /api/admin/** from JSON fixtures, never from
// an importer. scripts/admin-screenshots.sh starts it; it is not a mock of the importer's rules.
//
// Usage: node scripts/admin-fixture-server.mjs <dist dir> <fixtures dir> <port>
//
// GET  /api/admin/<path>   <fixtures>/<path>.json. Else the item of <fixtures>/<parent>.json's `content` whose slug or
//                          id is the last segment. A list is filtered by `name` (part of it, any case) and paged by
//                          `page` and `size`, at most 100, as the importer does. Other query parameters are ignored.
// Other methods            the first rule in <fixtures>/writes.json whose method and path match, and whose `when`
//                          fields equal the request body's. `{name}` in a rule's path matches one segment.
//
// A rule answers `status` with `body`. `"$merge"` as the body is the resource at the same path with the request body
// and the rule's `merge` on top. `then` maps a path to fields that later GETs of it show, which is how a row moves
// to RUNNING after an import. In strings, `{name}` is the matched segment and `$now` is the current instant.
// POST /__reset drops what the rules changed, so each screenshot starts from the fixtures as committed.

import { createServer } from 'node:http'
import { readFile, stat } from 'node:fs/promises'
import { extname, join, normalize, resolve } from 'node:path'

const [distArg, fixturesArg, portArg] = process.argv.slice(2)
if (!distArg || !fixturesArg || !portArg) {
  console.error('usage: admin-fixture-server.mjs <dist dir> <fixtures dir> <port>')
  process.exit(2)
}
const DIST = resolve(distArg)
const FIXTURES = resolve(fixturesArg)
const TYPES = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.json': 'application/json', '.ico': 'image/x-icon', '.woff2': 'font/woff2' }

/** What the write rules changed, by fixture path. Lives as long as the server, which is one screenshot run. */
const overlay = new Map()

async function readJson(file) {
  try {
    return JSON.parse(await readFile(file, 'utf8'))
  } catch (e) {
    if (e.code === 'ENOENT') return undefined
    throw e
  }
}

function fill(value, params, now) {
  if (typeof value === 'string') {
    if (value === '$now') return now
    return value.replace(/\{(\w+)\}/g, (match, name) => params[name] ?? match)
  }
  if (Array.isArray(value)) return value.map((v) => fill(v, params, now))
  if (value && typeof value === 'object') return Object.fromEntries(Object.entries(value).map(([k, v]) => [k, fill(v, params, now)]))
  return value
}

/** The resource at `path` as a GET shows it, with the overlay applied; undefined when no fixture has it. */
async function resource(path) {
  let found = await readJson(join(FIXTURES, `${path}.json`))
  if (found === undefined && path.includes('/')) {
    const cut = path.lastIndexOf('/')
    const parent = await readJson(join(FIXTURES, `${path.slice(0, cut)}.json`))
    const key = decodeURIComponent(path.slice(cut + 1))
    found = parent?.content?.find((item) => item.slug === key || String(item.id) === key)
  }
  if (found === undefined) return undefined
  // A list shows the overlay of each item, so the row an action changed changes in it too.
  if (Array.isArray(found.content)) {
    found = { ...found, content: found.content.map((item) => ({ ...item, ...overlay.get(`${path}/${item.slug ?? item.id}`) })) }
  }
  return { ...found, ...overlay.get(path) }
}

function match(pattern, path) {
  const want = pattern.split('/')
  const got = path.split('/')
  if (want.length !== got.length) return null
  const params = {}
  for (let i = 0; i < want.length; i++) {
    const name = /^\{(\w+)\}$/.exec(want[i])?.[1]
    if (name) params[name] = decodeURIComponent(got[i])
    else if (want[i] !== got[i]) return null
  }
  return params
}

async function write(method, path, requestBody) {
  const rules = (await readJson(join(FIXTURES, 'writes.json'))) ?? []
  const now = new Date().toISOString()
  for (const rule of rules) {
    if (rule.method !== method) continue
    const params = match(rule.path, path)
    if (!params) continue
    if (rule.when && !Object.entries(rule.when).every(([k, v]) => requestBody?.[k] === v)) continue
    for (const [target, fields] of Object.entries(rule.then ?? {})) {
      const key = fill(target, params, now)
      overlay.set(key, { ...overlay.get(key), ...fill(fields, params, now) })
    }
    let body = fill(rule.body ?? null, params, now)
    if (body === '$merge') body = { ...(await resource(path)), ...requestBody, ...fill(rule.merge ?? {}, params, now) }
    return { status: rule.status ?? 200, body }
  }
  return { status: 404, body: { status: 404, detail: `No fixture rule for ${method} /api/admin/${path}` } }
}

function send(res, status, body) {
  res.writeHead(status, { 'Content-Type': 'application/json' })
  res.end(body === null || status === 204 ? undefined : JSON.stringify(body))
}

async function readBody(req) {
  const chunks = []
  for await (const chunk of req) chunks.push(chunk)
  const text = Buffer.concat(chunks).toString('utf8')
  return text ? JSON.parse(text) : undefined
}

async function api(req, res, url) {
  const path = url.pathname.slice('/api/admin/'.length).replace(/\/+$/, '')
  if (req.method === 'GET') {
    let found = await resource(path)
    if (found === undefined) return send(res, 404, { status: 404, detail: `No fixture for /api/admin/${path}` })
    if (Array.isArray(found.content)) {
      // `name` matches part of the name, ignoring case, as the importer's artist and promoter lists do.
      const name = url.searchParams.get('name')?.trim().toLowerCase()
      if (name) {
        const content = found.content.filter((item) => String(item.name ?? '').toLowerCase().includes(name))
        found = { ...found, content, totalElements: content.length }
      }
      // The importer caps a page at 100, whatever `size` asks for.
      const size = Math.min(Number(url.searchParams.get('size') ?? 20), 100)
      const page = Number(url.searchParams.get('page') ?? 0)
      return send(res, 200, { ...found, content: found.content.slice(page * size, (page + 1) * size), size, number: page })
    }
    return send(res, 200, found)
  }
  const { status, body } = await write(req.method, path, await readBody(req))
  return send(res, status, body)
}

async function file(res, pathname) {
  // An unknown path is a client route: index.html renders it.
  let target = normalize(join(DIST, decodeURIComponent(pathname)))
  if (!target.startsWith(DIST) || !(await stat(target).catch(() => null))?.isFile()) target = join(DIST, 'index.html')
  res.writeHead(200, { 'Content-Type': TYPES[extname(target)] ?? 'application/octet-stream' })
  res.end(await readFile(target))
}

createServer((req, res) => {
  const url = new URL(req.url, 'http://localhost')
  if (req.method === 'POST' && url.pathname === '/__reset') {
    overlay.clear()
    return send(res, 204, null)
  }
  const handled = url.pathname.startsWith('/api/admin/') ? api(req, res, url) : file(res, url.pathname)
  handled.catch((e) => {
    console.error(e)
    // The error goes to the log above, not into the response: a Problem Detail names no internals.
    if (!res.headersSent) send(res, 500, { status: 500, detail: 'Fixture server error; see its log.' })
  })
}).listen(Number(portArg), '127.0.0.1', () => console.error(`admin fixtures on http://127.0.0.1:${portArg}`))
