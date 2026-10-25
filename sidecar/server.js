/**
 * 豆瓣缓存 sidecar。
 *
 * 为什么需要它：客户端直连豆瓣，冷启动要打 10+ 个请求，家宽 IP 必然被风控
 * （HTTP 200 但 body 是 {"msg":"检测到有异常请求…","r":1}）。
 * 放到路由器上以后：
 *   1) 缓存共享 —— 电视、手机、重装后的客户端都吃同一份 24h 缓存；
 *   2) 单飞 —— 同一个 URL 并发只打一次上游；
 *   3) 串行限速 —— 全局 2.5s 一个请求，永远不并发；
 *   4) 陈旧兜底 —— 上游失败时返回过期缓存（X-Cache: stale），绝不空手。
 */
const http = require('http');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

const PORT = Number(process.env.PORT || 8898);
const DIR = process.env.CACHE_DIR || '/data/cache';
const TTL = Number(process.env.TTL_SECONDS || 86400) * 1000;
const GAP = Number(process.env.MIN_GAP_MS || 2500);
// 0 = 只读缓存，绝不访问豆瓣（开发/验收期用这个模式）
const ALLOW_UPSTREAM = process.env.ALLOW_UPSTREAM !== '0';
const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36';

fs.mkdirSync(DIR, { recursive: true });

const PENDING = path.join(DIR, 'pending.json');
const loadPending = () => { try { return JSON.parse(fs.readFileSync(PENDING, 'utf8')); } catch { return []; } };
const savePending = (list) => { try { fs.writeFileSync(PENDING, JSON.stringify(list.slice(0, 40))); } catch {} };
const addPending = (p) => { const l = loadPending(); if (!l.includes(p)) { l.push(p); savePending(l); console.log('pending +', p); } };
const dropPending = (p) => savePending(loadPending().filter((x) => x !== p));

const key = (p) => crypto.createHash('md5').update(p).digest('hex');
const inflight = new Map();
let lastFetch = 0;

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function readCache(k) {
  try {
    const f = path.join(DIR, k + '.json');
    const st = fs.statSync(f);
    return { body: fs.readFileSync(f, 'utf8'), age: Date.now() - st.mtimeMs };
  } catch { return null; }
}

function writeCache(k, body) {
  try { fs.writeFileSync(path.join(DIR, k + '.json'), body); } catch (e) { console.error('write cache', e.message); }
}

async function fetchUpstreamOnce(doubanPath) {
  const gap = GAP - (Date.now() - lastFetch);
  if (gap > 0) await sleep(gap);
  lastFetch = Date.now();

  const url = 'https://movie.douban.com' + doubanPath;
  const ctl = new AbortController();
  const timer = setTimeout(() => ctl.abort(), 15000);
  try {
    const r = await fetch(url, {
      signal: ctl.signal,
      headers: { 'User-Agent': UA, Referer: 'https://movie.douban.com/', Accept: 'application/json,text/html;q=0.9,*/*;q=0.8' },
    });
    const body = await r.text();
    if (body.includes('检测到有异常请求') || body.includes('"r":1')) {
      const e = new Error('RATE_LIMITED');
      e.body = body;
      throw e;
    }
    return body;
  } finally { clearTimeout(timer); }
}

async function fetchUpstream(doubanPath) {
  try {
    return await fetchUpstreamOnce(doubanPath);
  } catch (e) {
    // 豆瓣按 IP 限流，隔一会儿再试通常就过了；只重试一次，避免把自己拖死
    if (e.message === 'RATE_LIMITED') {
      await sleep(6000);
      return await fetchUpstreamOnce(doubanPath);
    }
    throw e;
  }
}

const server = http.createServer(async (req, res) => {
  const u = new URL(req.url, 'http://x');
  if (u.pathname === '/healthz') {
    return send(res, 200, JSON.stringify({ ok: true, cached: fs.readdirSync(DIR).length, port: PORT }));
  }
  if (u.pathname !== '/douban') return send(res, 404, '{"error":"not found"}');

  const p = u.searchParams.get('path');
  if (!p || !p.startsWith('/')) return send(res, 400, '{"error":"path required"}');

  const k = key(p);
  const hit = readCache(k);
  if (hit && hit.age < TTL) {
    res.setHeader('X-Cache', 'hit');
    return send(res, 200, hit.body);
  }

  if (!ALLOW_UPSTREAM) {
    const stale = readCache(k);
    if (stale) {
      res.setHeader('X-Cache', 'stale-offline');
      return send(res, 200, stale.body);
    }
    res.setHeader('X-Cache', 'offline-miss');
    return send(res, 200, JSON.stringify({ msg: 'offline mode: no upstream access', r: 1 }));
  }

  if (inflight.has(k)) {
    try {
      const body = await inflight.get(k);
      res.setHeader('X-Cache', 'coalesced');
      return send(res, 200, body);
    } catch (e) { /* 落到下面走 stale */ }
  }

  let p2;
  if (!inflight.has(k)) {
    p2 = fetchUpstream(p).finally(() => inflight.delete(k));
    inflight.set(k, p2);
  } else p2 = inflight.get(k);

  try {
    const body = await p2;
    writeCache(k, body);
    res.setHeader('X-Cache', 'miss');
    return send(res, 200, body);
  } catch (e) {
    const stale = readCache(k);
    if (stale) {
      res.setHeader('X-Cache', 'stale');
      return send(res, 200, stale.body);
    }
    console.error('upstream fail', p, e.message);
    if (e.message === 'RATE_LIMITED') addPending(p);
    return send(res, 200, JSON.stringify({ msg: 'upstream failed: ' + e.message, r: 1 }));
  }
});

function send(res, code, body) {
  res.writeHead(code, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' });
  res.end(body);
}

server.listen(PORT, '0.0.0.0', () => console.log(`douban sidecar on :${PORT}, cache=${DIR}, ttl=${TTL / 1000}s, gap=${GAP}ms, upstream=${ALLOW_UPSTREAM ? 'on' : 'OFF(cache-only)'}`));

/**
 * 补课循环：限流期间没抓到的 URL 记在 pending.json，每 10 分钟试一次（一次只试一个），
 * 抓到就写缓存并出队。这样客户端不需要为"当时被限流"负责，下次打开自然就有。
 */
setInterval(async () => {
  if (!ALLOW_UPSTREAM) return;
  const list = loadPending();
  if (!list.length) return;
  const p = list[0];
  if (readCache(key(p))) { dropPending(p); return; }
  try {
    const body = await fetchUpstream(p);
    writeCache(key(p), body);
    dropPending(p);
    console.log('pending done', p);
  } catch (e) {
    console.log('pending retry failed', e.message);
  }
}, 10 * 60 * 1000);
