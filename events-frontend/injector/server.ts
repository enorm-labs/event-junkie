import { createServer, type IncomingMessage, type ServerResponse } from 'node:http'

import { bffPath, entityMeta } from './meta.ts'
import { rewriteHead } from './rewrite.ts'
import { matchDetailRoute, matchStaticRoute } from './routes.ts'
import { staticPathMeta } from '../src/lib/staticPages.ts'

/**
 * The meta-injection sidecar, ADR-014 §Decision 3's transport. nginx proxies the four detail
 * route families and the static pages here; each request fetches the shell from nginx on loopback
 * and, for a detail page, the entity from the BFF, rewrites the head, and answers. Any failure is a 502 and nothing else: nginx's
 * `error_page` then serves the plain shell, so every branch that is not the happy path ends in
 * `fail()`. Nothing about the visitor reaches the BFF, and nothing is logged per request. Two
 * in-process caches bound the BFF load: the shell changes only on deploy, and a link shared into
 * a busy group is fetched by every scraper at once.
 */

const PORT = Number(process.env.INJECTOR_PORT ?? 3000)
const SHELL_URL = process.env.SHELL_URL ?? 'http://127.0.0.1:8080/index.html'
const BFF_URL = (process.env.BFF_URL ?? 'http://127.0.0.1:8081').replace(/\/$/, '')
const BFF_TIMEOUT_MS = Number(process.env.BFF_TIMEOUT_MS ?? 1500)
const SHELL_TTL_MS = 5 * 60 * 1000
const ENTITY_TTL_MS = 60 * 1000
const ENTITY_CACHE_LIMIT = 500
/** A shell or an entity larger than this is not ours; refuse it rather than buffer it. */
const MAX_BODY_BYTES = 1024 * 1024

class Fail extends Error {
  constructor(
    message: string,
    readonly status: number,
  ) {
    super(message)
  }
}

async function readBody(url: string, accept: string): Promise<string> {
  const response = await fetch(url, {
    headers: { accept },
    signal: AbortSignal.timeout(BFF_TIMEOUT_MS),
  }).catch((error: Error) => {
    // Undici says "fetch failed" and keeps the reason in `cause`; the log line needs which URL.
    throw new Fail(`${url}: ${(error.cause as Error | undefined)?.message ?? error.message}`, 502)
  })
  if (!response.ok) throw new Fail(`${url} answered ${response.status}`, response.status)
  const length = Number(response.headers.get('content-length') ?? 0)
  if (length > MAX_BODY_BYTES) throw new Fail(`${url} is ${length} bytes`, 502)
  const body = await response.text()
  if (body.length > MAX_BODY_BYTES) throw new Fail(`${url} is over ${MAX_BODY_BYTES} bytes`, 502)
  return body
}

let shell: { html: string; fetchedAt: number } | undefined

async function loadShell(): Promise<string> {
  if (shell && Date.now() - shell.fetchedAt < SHELL_TTL_MS) return shell.html
  const html = await readBody(SHELL_URL, 'text/html')
  if (!html.includes('</head>')) throw new Fail(`${SHELL_URL} did not return the shell`, 502)
  shell = { html, fetchedAt: Date.now() }
  return html
}

const entities = new Map<string, { value: unknown; fetchedAt: number }>()

async function loadEntity(path: string): Promise<unknown> {
  const cached = entities.get(path)
  if (cached && Date.now() - cached.fetchedAt < ENTITY_TTL_MS) return cached.value

  const value: unknown = JSON.parse(await readBody(BFF_URL + path, 'application/json'))
  // Insertion order is age, so the oldest entry is the first one — a cap, not an LRU, and enough.
  if (entities.size >= ENTITY_CACHE_LIMIT) entities.delete(entities.keys().next().value as string)
  entities.set(path, { value, fetchedAt: Date.now() })
  return value
}

function send(response: ServerResponse, body: string): void {
  response
    .writeHead(200, {
      'content-type': 'text/html; charset=utf-8',
      // The shell's own rule, kept on the rewritten copy: a cached one pins a browser to files a
      // deploy has deleted.
      'cache-control': 'no-cache',
    })
    .end(body)
}

function fail(response: ServerResponse, status: number, reason: string): void {
  if (status !== 404) console.error(`injector: ${reason}`)
  response.writeHead(status, { 'content-type': 'text/plain; charset=utf-8' }).end(reason)
}

async function handle(request: IncomingMessage, response: ServerResponse): Promise<void> {
  const url = request.url ?? '/'
  if (url === '/healthz') {
    response.writeHead(200, { 'content-type': 'text/plain' }).end('ok')
    return
  }

  // A static page needs no entity: its head is the catalogue's.
  const page = matchStaticRoute(url)
  if (page) {
    try {
      const meta = staticPathMeta(page.locale, page.path)
      send(response, rewriteHead(await loadShell(), { meta, locale: page.locale, path: page.path }))
    } catch (error) {
      fail(response, 502, `${page.locale}${page.path}: ${(error as Error).message}`)
    }
    return
  }

  const route = matchDetailRoute(url)
  if (!route) {
    fail(response, 404, `not an injected route: ${url}`)
    return
  }

  try {
    const [html, entity] = await Promise.all([
      loadShell(),
      loadEntity(bffPath(route.kind, route.slug)),
    ])
    const { meta, image } = entityMeta(route.kind, entity, route.locale)
    send(response, rewriteHead(html, { meta, image, locale: route.locale, path: route.path }))
  } catch (error) {
    // A 404 from the BFF is an unknown slug, which the SPA renders as its own not-found page.
    const status = error instanceof Fail && error.status === 404 ? 404 : 502
    fail(response, status, `${route.kind}/${route.slug}: ${(error as Error).message}`)
  }
}

createServer((request, response) => {
  handle(request, response).catch((error: unknown) => {
    fail(response, 502, `unhandled: ${(error as Error).message}`)
  })
}).listen(PORT, () => {
  console.log(
    `injector: listening on ${PORT}, shell ${SHELL_URL}, bff ${BFF_URL} (${BFF_TIMEOUT_MS}ms)`,
  )
})
