/**
 * 豆瓣缓存 sidecar（v2）
 *
 * 设计目标不是「更快地打豆瓣」，而是「尽量不打豆瓣」：
 *   1) 分批预热：每 30 分钟一批（默认 10 个），一天内补齐榜单矩阵，不连续撞墙；
 *   2) TTL 7 天：榜单按天变化，缓存 3 天的数据没人会抱怨，但限流会；
 *   3) 语义化 X-Cache：客户端读头判态，不再靠猜 body（v1 的假限流就出在这）；
 *   4) 真限流即熔断 30 分钟 —— 实测连续 16 个请求就会触发，所以必须分批 + 熔断；
 *   5) 上游失败/限流返回 stale，绝不空手。
 *
 * 协议：
 *   GET /douban?path=<encoded path>[&host=www.douban.com]
 *       X-Cache: hit | miss | coalesced | stale | stale-offline | rate-limited | offline-miss | upstream-error
 *       200 = 有内容；429 = 上游限流且无 stale；503 = 没开上游 / 上游错误且无 stale
 *   GET  /healthz           → 库存 / 熔断 / 预热进度
 *   POST /prewarm[?limit=N] → 202，后台跑一批（默认 PREWARM_BATCH 个未缓存目标）
 *   GET  /prewarm/status    → 进度
 *   GET  /stats             → 命中率统计
 */
const http = require('http');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

const PORT = Number(process.env.PORT || 8898);
const DIR = process.env.CACHE_DIR || '/data/cache';
const TTL = Number(process.env.TTL_SECONDS || 7 * 86400) * 1000;
const GAP = Number(process.env.MIN_GAP_MS || 6000);
const ALLOW_UPSTREAM = process.env.ALLOW_UPSTREAM !== '0';
const BATCH = Number(process.env.PREWARM_BATCH || 10);          // 单批上限
const BATCH_EVERY_MIN = Number(process.env.PREWARM_EVERY_MIN || 30);
const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36';

const M = 'movie.douban.com';
const W = 'www.douban.com';
const HOSTS = [M, W];

fs.mkdirSync(DIR, { recursive: true });

// ---------- 待补队列 ----------
// 条目格式 "host/path"（如 "movie.douban.com/top250?start=0"）：可读、可直接补、不怕 v1 的 key 换代。
const PENDING = path.join(DIR, 'pending.json');
const pid = (host, p) => host + p;
const loadPending = () => {
  try {
    const list = JSON.parse(fs.readFileSync(PENDING, 'utf8'));
    // 丢掉 v1 遗留的裸 path 与非法项
    return list.filter((x) => typeof x === 'string' && HOSTS.some((h) => x.startsWith(h + '/')));
  } catch { return []; }
};
const savePending = (list) => { try { fs.writeFileSync(PENDING, JSON.stringify(list.slice(0, 80))); } catch {} };
const addPending = (id) => { const l = loadPending(); if (!l.includes(id)) { l.push(id); savePending(l); console.log('pending +', id); } };
const dropPending = (id) => savePending(loadPending().filter((x) => x !== id));

function splitId(id) {
  for (const h of HOSTS) if (id.startsWith(h + '/')) return { host: h, path: id.slice(h.length) };
  return null;
}

// ---------- 缓存 ----------
// v1 的 key 是 md5(path)：movie 保持原算法让旧缓存继续有效，其它 host 前缀区分，避免撞 key。
const cacheKey = (host, p) => crypto.createHash('md5').update(host === M ? p : host + p).digest('hex');
const cacheFile = (k) => path.join(DIR, k + '.json');

function readCache(k) {
  try {
    const f = cacheFile(k);
    const st = fs.statSync(f);
    return { body: fs.readFileSync(f, 'utf8'), age: Date.now() - st.mtimeMs };
  } catch { return null; }
}
function writeCache(k, body) {
  try { fs.writeFileSync(cacheFile(k), body); } catch (e) { console.error('write cache', e.message); }
}
const isFresh = (t) => {
  const c = readCache(cacheKey(t.host, t.path));
  return !!c && c.age < TTL;
};

// ---------- 上游 ----------
const inflight = new Map();
let lastFetch = 0;
let limitedUntil = 0;          // 熔断到期时间
let consecutiveLimited = 0;
const stats = { hit: 0, miss: 0, coalesced: 0, stale: 0, rateLimited: 0, offlineMiss: 0 };
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const hitRateLimit = (body) => body.includes('检测到有异常请求') || body.includes('"r":1');

async function fetchUpstreamOnce(host, doubanPath) {
  const gap = GAP - (Date.now() - lastFetch);
  if (gap > 0) await sleep(gap);
  lastFetch = Date.now();

  const ctl = new AbortController();
  const timer = setTimeout(() => ctl.abort(), 15000);
  try {
    const r = await fetch('https://' + host + doubanPath, {
      signal: ctl.signal,
      headers: { 'User-Agent': UA, Referer: 'https://movie.douban.com/', Accept: 'application/json,text/html;q=0.9,*/*;q=0.8' },
    });
    const body = await r.text();
    if (hitRateLimit(body)) {
      const e = new Error('RATE_LIMITED');
      e.body = body;
      throw e;
    }
    return body;
  } finally { clearTimeout(timer); }
}

/** 命中一次限流即熔断 30 分钟：豆瓣按 IP 记账，越急越封，重试是负收益 */
async function fetchUpstream(host, doubanPath) {
  if (Date.now() < limitedUntil) {
    const e = new Error('RATE_LIMITED');
    e.circuit = true;
    throw e;
  }
  try {
    const body = await fetchUpstreamOnce(host, doubanPath);
    consecutiveLimited = 0;
    return body;
  } catch (e) {
    if (e.message === 'RATE_LIMITED') {
      consecutiveLimited++;
      limitedUntil = Date.now() + 30 * 60_000;
      console.error(`circuit OPEN 30min (limited=${consecutiveLimited})`);
    }
    throw e;
  }
}

// ---------- 预热矩阵：与客户端 Shelves / presets / BrowseScreen 一一对应 ----------
const javaEnc = (s) => encodeURIComponent(s)
  .replace(/,/g, '%2C').replace(/~/g, '%7E').replace(/'/g, '%27')
  .replace(/\(/g, '%28').replace(/\)/g, '%29').replace(/!/g, '%21');

/** 与 DoubanUrls.search 同形：range 必须是一位小数（0.0 / 7.5 / 9.0），否则缓存键对不上 */
const searchUrl = (tags, year, minRate, sort = 'T', start = 0) => {
  const all = [].concat(tags || [], year ? [year] : []).join(',');
  const min = Number(minRate || 0).toFixed(1);
  return '/j/new_search_subjects?sort=' + sort + '&range=' + min + ',10&tags=' + javaEnc(all) + '&start=' + start;
};

const PREWARM_TARGETS = [
  // 首页「电影」行（Shelves.movie）
  { host: M, path: '/j/search_subjects?type=movie&tag=' + javaEnc('热门') + '&sort=recommend&page_limit=40&page_start=0' },
  { host: M, path: '/top250?start=0' },
  { host: M, path: '/top250?start=25' },
  { host: M, path: searchUrl(['电影'], null, 9.0) },
  { host: M, path: searchUrl(['电影'], '2025', 0) },
  // 榜单中心 presets
  { host: M, path: searchUrl(['电影'], '2024', 0) },
  { host: M, path: searchUrl(['华语'], null, 7.5) },
  { host: M, path: searchUrl(['韩国'], null, 7.5) },
  { host: M, path: searchUrl(['日本'], null, 7.5) },
  { host: M, path: searchUrl(['悬疑'], null, 7.5) },
  { host: W, path: '/doulist/240962/?sort=seq&playable=0' },
  // 首页「剧集」行 + presets
  { host: M, path: searchUrl(['国产剧'], '2025', 0) },
  { host: M, path: searchUrl(['韩剧'], '2025', 0) },
  { host: M, path: searchUrl(['日剧'], '2025', 0) },
  { host: M, path: searchUrl(['美剧'], null, 0) },
  // 首页「综艺 · 纪录片」行
  { host: M, path: searchUrl(['综艺'], null, 0) },
  { host: M, path: searchUrl(['纪录片'], null, 8.5) },
  // 浏览页单维度常用筛选（电影 base 叠加一个维度）
  { host: M, path: searchUrl(['电影', '2025'], null, 9.0) },
  { host: M, path: searchUrl(['电影', '2024'], null, 9.0) },
  { host: M, path: searchUrl(['电影', '2023'], null, 9.0) },
  { host: M, path: searchUrl(['电影', '华语'], null, 9.0) },
  { host: M, path: searchUrl(['电影', '欧美'], null, 9.0) },
  { host: M, path: searchUrl(['电影', '韩国'], null, 9.0) },
  { host: M, path: searchUrl(['电影', '日本'], null, 9.0) },
  { host: M, path: searchUrl(['电影', '剧情'], null, 9.0) },
  { host: M, path: searchUrl(['电影', '悬疑'], null, 9.0) },
  { host: M, path: searchUrl(['电影', '科幻'], null, 9.0) },
  { host: M, path: searchUrl(['电影', '喜剧'], null, 9.0) },
  { host: M, path: searchUrl(['电影', '爱情'], null, 9.0) },
  { host: M, path: searchUrl(['电影', '动作'], null, 9.0) },
  { host: M, path: searchUrl(['电影', '动画'], null, 9.0) },
  // 浏览页默认排序（用户进页面不选筛选时的第一屏）
  { host: M, path: searchUrl(['电影'], null, 9.0, 'S') },
  { host: M, path: searchUrl(['国产剧'], '2025', 0, 'S') },
  { host: M, path: searchUrl(['综艺'], null, 0, 'S') },
];

const pre = { running: false, done: 0, total: 0, ok: 0, failed: 0, startedAt: 0, lastRun: 0, lastResult: '', batch: BATCH };

/**
 * 跑一批预热。
 * 无 limit：只挑「还没新鲜缓存」的目标，最多 BATCH 个 —— 这是常态；
 * 有 limit：按矩阵顺序取前 N 个（冒烟/调试用），同样受熔断保护。
 */
async function runPrewarm(limit) {
  if (pre.running) return { started: false, reason: 'already running' };
  if (!ALLOW_UPSTREAM) return { started: false, reason: 'ALLOW_UPSTREAM=0' };
  if (Date.now() < limitedUntil) return { started: false, reason: 'circuit open' };

  const pool = limit ? PREWARM_TARGETS.slice(0, limit) : PREWARM_TARGETS.filter((t) => !isFresh(t));
  const list = limit ? pool : pool.slice(0, BATCH);
  if (!list.length) { pre.lastResult = 'all fresh'; pre.lastRun = Date.now(); return { started: false, reason: 'all fresh' }; }

  pre.running = true;
  pre.done = 0; pre.ok = 0; pre.failed = 0;
  pre.total = list.length;
  pre.startedAt = Date.now();
  pre.lastResult = '';

  for (const t of list) {
    if (isFresh(t)) { pre.done++; pre.ok++; continue; }   // 已新鲜，绝不重复打
    try {
      const body = await fetchUpstream(t.host, t.path);
      writeCache(cacheKey(t.host, t.path), body);
      dropPending(pid(t.host, t.path));
      pre.ok++;
    } catch (e) {
      pre.failed++;
      if (e.message === 'RATE_LIMITED') {
        addPending(pid(t.host, t.path));
        if (Date.now() < limitedUntil) { pre.lastResult = 'aborted: rate limited'; break; }
      }
      console.log('prewarm fail', pid(t.host, t.path).slice(0, 70), e.message);
    }
    pre.done++;
  }
  pre.running = false;
  pre.lastRun = Date.now();
  if (!pre.lastResult) pre.lastResult = `ok=${pre.ok} failed=${pre.failed}`;
  console.log('prewarm done', pre.lastResult, `in ${Math.round((Date.now() - pre.startedAt) / 1000)}s`);
  return { started: true, status: pre };
}

// ---------- HTTP ----------
const server = http.createServer(async (req, res) => {
  const u = new URL(req.url, 'http://x');

  if (u.pathname === '/healthz') {
    const cached = fs.readdirSync(DIR).filter((f) => f.endsWith('.json') && !f.startsWith('pending')).length;
    return send(res, 200, JSON.stringify({
      ok: true, cached, pending: loadPending().length,
      ttl: TTL / 1000, gap: GAP, batch: BATCH,
      upstream: ALLOW_UPSTREAM ? 'on' : 'OFF(cache-only)',
      circuitOpenFor: Math.max(0, Math.round((limitedUntil - Date.now()) / 1000)),
      prewarm: pre,
    }));
  }

  if (u.pathname === '/stats') return send(res, 200, JSON.stringify(stats));
  if (u.pathname === '/prewarm/status') return send(res, 200, JSON.stringify(pre));

  if (u.pathname === '/prewarm') {
    const limit = Number(u.searchParams.get('limit') || 0) || undefined;
    runPrewarm(limit).then((r) => { if (!r.started) console.log('prewarm skipped:', r.reason); });
    return send(res, 202, JSON.stringify({ accepted: true, batch: limit || BATCH }));
  }

  if (u.pathname !== '/douban') return send(res, 404, '{"error":"not found"}');

  const p = u.searchParams.get('path');
  const host = u.searchParams.get('host') || M;
  if (!p || !p.startsWith('/')) return send(res, 400, '{"error":"path required"}');
  if (!HOSTS.includes(host)) return send(res, 400, '{"error":"host not allowed"}');

  const k = cacheKey(host, p);

  // 1) 新鲜缓存
  const hit = readCache(k);
  if (hit && hit.age < TTL) {
    stats.hit++;
    return send(res, 200, hit.body, { 'X-Cache': 'hit', 'X-Age': String(Math.round(hit.age / 1000)) });
  }

  // 2) 没开上游：只说「没准备好」，不冒充豆瓣限流
  if (!ALLOW_UPSTREAM) {
    if (hit) {
      stats.stale++;
      return send(res, 200, hit.body, { 'X-Cache': 'stale-offline', 'X-Age': String(Math.round(hit.age / 1000)) });
    }
    stats.offlineMiss++;
    return send(res, 503, JSON.stringify({ error: 'offline_miss' }), { 'X-Cache': 'offline-miss' });
  }

  // 3) 单飞：同一个 URL 并发只打一次上游
  let owner = false;
  let flight = inflight.get(k);
  if (!flight) {
    flight = fetchUpstream(host, p).finally(() => inflight.delete(k));
    inflight.set(k, flight);
    owner = true;
  }

  try {
    const body = await flight;
    if (owner) { writeCache(k, body); stats.miss++; return send(res, 200, body, { 'X-Cache': 'miss' }); }
    stats.coalesced++;
    return send(res, 200, body, { 'X-Cache': 'coalesced' });
  } catch (e) {
    const stale = readCache(k);
    if (stale) {
      stats.stale++;
      return send(res, 200, stale.body, { 'X-Cache': 'stale', 'X-Age': String(Math.round(stale.age / 1000)) });
    }
    if (e.message === 'RATE_LIMITED') {
      stats.rateLimited++;
      addPending(pid(host, p));
      const retry = Math.max(1, Math.round((limitedUntil - Date.now()) / 1000));
      return send(res, 429, JSON.stringify({ error: 'rate_limited', retryAfter: retry }), {
        'X-Cache': 'rate-limited', 'Retry-After': String(retry),
      });
    }
    console.error('upstream fail', pid(host, p).slice(0, 70), e.message);
    return send(res, 503, JSON.stringify({ error: 'upstream_error', detail: e.message }), { 'X-Cache': 'upstream-error' });
  }
});

function send(res, code, body, headers) {
  res.writeHead(code, Object.assign({
    'Content-Type': 'application/json; charset=utf-8',
    'Cache-Control': 'no-store',
  }, headers || {}));
  res.end(body);
}

server.listen(PORT, '0.0.0.0', () => {
  console.log(`douban sidecar v2 on :${PORT}, cache=${DIR}, ttl=${TTL / 1000}s, gap=${GAP}ms, batch=${BATCH}, ` +
    `upstream=${ALLOW_UPSTREAM ? 'on' : 'OFF(cache-only)'}, targets=${PREWARM_TARGETS.length}`);
});

// ---------- 定时：每 BATCH_EVERY_MIN 分钟补一批，白天只在有缺口时动 ----------
setInterval(() => {
  if (!ALLOW_UPSTREAM) return;
  if (Date.now() < limitedUntil) return;
  if (pre.running) return;
  const missing = PREWARM_TARGETS.filter((t) => !isFresh(t)).length;
  if (!missing) return;
  console.log(`scheduled prewarm: ${missing} missing`);
  runPrewarm();
}, BATCH_EVERY_MIN * 60_000);

// ---------- 补课：限流期间没抓到的，解封后一次补一个 ----------
setInterval(async () => {
  if (!ALLOW_UPSTREAM || Date.now() < limitedUntil) return;
  const item = loadPending()[0];
  if (!item) return;
  const t = splitId(item);
  if (!t) { dropPending(item); return; }
  if (readCache(cacheKey(t.host, t.path))) { dropPending(item); return; }
  try {
    const body = await fetchUpstream(t.host, t.path);
    writeCache(cacheKey(t.host, t.path), body);
    dropPending(item);
    console.log('pending done', item.slice(0, 70));
  } catch (e) {
    console.log('pending retry failed', e.message);
  }
}, 10 * 60 * 1000);
