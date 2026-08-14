"use strict";

require("./dotenv"); // loads backend/.env so credentials can live in a file

const express = require("express");
const cors = require("cors");
const crypto = require("crypto");
const fs = require("fs");
const fsp = require("fs/promises");
const path = require("path");
const { DailyRotationSource, PartnerApiSource, KrogerLiveSource } = require("./src/sources");

const app = express();
app.use(cors());
app.use(express.json({ limit: "2mb" }));

const PORT = Number(process.env.PORT || 4000) || 4000;

// ---------------------------------------------------------------------------
// Error handling — malformed request data must NEVER terminate the process.
// Express 4 does not catch async rejections, so every async route is wrapped in
// asyncRoute(); anything that still escapes lands in the central error handler
// and the process-level guards below (which log and keep serving).
// ---------------------------------------------------------------------------
const asyncRoute = (fn) => (req, res, next) => Promise.resolve(fn(req, res, next)).catch(next);

// Update channel: advertise the latest release APK served by this backend so the
// app can show an in-app update card after a successful sync. Drop the APK at
// backend/public/Thrive-release.apk. The advertised version defaults to the
// version actually built into the app (read from app/build.gradle.kts); only an
// UPDATE_VERSION env override (e.g. to stage a newer build) changes it. The app
// compares versions and shows the card only when the advertised version is newer
// than the installed one.
const UPDATE_VERSION = process.env.UPDATE_VERSION || null;
const APK_PATH = path.join(__dirname, "public", "Thrive-release.apk");

function installedVersion() {
  if (UPDATE_VERSION) return UPDATE_VERSION;
  try {
    const gradle = fs.readFileSync(path.join(__dirname, "..", "app", "build.gradle.kts"), "utf-8");
    const m = gradle.match(/versionName\s*=\s*"([^"]+)"/);
    if (m) return m[1];
  } catch {
    /* fall through */
  }
  return "0.0.0";
}

const UPDATE_VERSION_NAME = installedVersion();
const APK_EXISTS = () => fs.existsSync(APK_PATH) && fs.statSync(APK_PATH).size > 0;

// Release notes per version (backend/release-notes.json) shown on the update
// card. Falls back to an empty list when the file is missing.
const RELEASE_NOTES = (() => {
  try {
    const raw = JSON.parse(fs.readFileSync(path.join(__dirname, "release-notes.json"), "utf-8"));
    return raw.notes || {};
  } catch {
    return {};
  }
})();
function notesFor(version) {
  return (RELEASE_NOTES[version] || []).slice(0, 5);
}
app.use("/releases", express.static(path.join(__dirname, "public")));

// ---------------------------------------------------------------------------
// Data + sources
// ---------------------------------------------------------------------------

function loadJson(rel) {
  const appAsset = path.join(__dirname, "..", "app", "src", "main", "assets", "data", rel);
  if (fs.existsSync(appAsset)) return JSON.parse(fs.readFileSync(appAsset, "utf-8"));
  return JSON.parse(fs.readFileSync(path.join(__dirname, "data", rel), "utf-8"));
}

const coupons = loadJson("coupons.json");
const recipes = loadJson("recipes.json");
const catalog = loadJson("catalog.json");

// ---------------------------------------------------------------------------
// Daily coupon freshness
// ---------------------------------------------------------------------------
// The bundled catalog is static, so the server stamps a deterministic daily
// rotation on top of it: roughly a fifth of the coupons are marked "new today"
// (rotating by day) and expiry counts drift day-to-day. Same shape a live
// feed would return, so the app always shows fresh-looking offers without any
// retailer API key.

function daySeed() {
  const now = new Date();
  const start = new Date(now.getFullYear(), 0, 0);
  return Math.floor((now - start) / 86400000);
}

function hashCoupon(s) {
  let h = 0;
  for (let i = 0; i < s.length; i++) {
    h = (Math.imul(h, 31) + s.charCodeAt(i)) | 0;
  }
  return Math.abs(h);
}

function rotateCoupons(day) {
  return coupons.map((c, i) => {
    const seed = hashCoupon(c.id + ":" + day) + i * 7;
    const isNew = seed % 5 === 0; // ~20% are "new" today, rotating daily
    const ends = 1 + (seed % 14);
    return { ...c, isNew, endsInDays: ends };
  });
}

const sources = [new DailyRotationSource(), new PartnerApiSource(), new KrogerLiveSource()].filter((s) => s.enabled !== false);

const VERSION = 3;

let dealsCache = null;
let dealsCacheAt = 0;
let payloadCache = null;
let payloadCacheAt = 0;

async function getDeals() {
  const todayKey = new Date().toISOString().slice(0, 10);
  if (dealsCache && dealsCacheAt === todayKey) return dealsCache;
  const merged = [];
  for (const source of sources) {
    try {
      const deals = await source.deals();
      if (Array.isArray(deals)) merged.push(...deals);
    } catch (err) {
      console.error(`[source:${source.name}] ${err.message}`);
    }
  }
  dealsCache = merged.length ? merged : loadJson("deals.json");
  dealsCacheAt = todayKey;
  payloadCache = null; // force payload rebuild with a fresh timestamp
  return dealsCache;
}

async function syncPayload() {
  const todayKey = new Date().toISOString().slice(0, 10);
  if (payloadCache && payloadCacheAt === todayKey) return payloadCache;
  const deals = await getDeals();
  payloadCache = {
    version: VERSION,
    generatedAt: new Date().toISOString(),
    source: sources.map((s) => s.name),
    deals,
    coupons: rotateCoupons(daySeed()),
    recipes,
    catalog,
  };
  payloadCacheAt = todayKey;
  return payloadCache;
}

// ---------------------------------------------------------------------------
// ETag helper
// ---------------------------------------------------------------------------

function etagFor(obj) {
  return '"' + crypto.createHash("sha1").update(JSON.stringify(obj)).digest("hex").slice(0, 16) + '"';
}

function respondWithEtag(request, response, body) {
  const etag = etagFor(body);
  response.set("ETag", etag);
  response.set("Cache-Control", "no-cache");
  const inm = request.headers["if-none-match"];
  if (inm && inm === etag) {
    response.status(304).end();
    return true;
  }
  return false;
}

// ---------------------------------------------------------------------------
// Routes
// ---------------------------------------------------------------------------

app.get("/api/v1/health", (req0, res) => {
  res.json({
    ok: true,
    service: "thrive-sync",
    version: VERSION,
    sources: sources.map((s) => s.name),
    time: new Date().toISOString(),
  });
});

// Strict query parsing: bad limit/category input is a 400, never NaN slicing.
function parseLimit(raw) {
  if (raw === undefined) return null;
  const n = Number(raw);
  if (!Number.isInteger(n) || n < 1 || n > 500) {
    const err = new Error("limit must be an integer between 1 and 500");
    err.status = 400;
    err.expose = true;
    throw err;
  }
  return n;
}

function parseCategory(raw) {
  if (raw === undefined) return null;
  const s = String(raw);
  if (s.length === 0 || s.length > 40 || /[\u0000-\u001f\u007f]/.test(s)) {
    const err = new Error("category must be 1-40 visible characters");
    err.status = 400;
    err.expose = true;
    throw err;
  }
  return s.toLowerCase();
}

app.get("/api/v1/deals", asyncRoute(async (req0, res) => {
  const deals = await getDeals();
  const category = parseCategory(req0.query.category);
  const limit = parseLimit(req0.query.limit);
  let out = deals;
  if (category) {
    // Defensive: never assume a source/deal has a string category.
    out = out.filter((d) => d && typeof d.category === "string" && d.category.toLowerCase() === category);
  }
  if (limit !== null) out = out.slice(0, limit);
  if (respondWithEtag(req0, res, out)) return;
  res.json({ deals: out, generatedAt: new Date().toISOString() });
}));

app.get("/api/v1/coupons", (req0, res) => {
  const category = parseCategory(req0.query.category);
  const limit = parseLimit(req0.query.limit);
  let out = rotateCoupons(daySeed());
  if (category) {
    out = out.filter((c) => c && typeof c.category === "string" && c.category.toLowerCase() === category);
  }
  if (limit !== null) out = out.slice(0, limit);
  if (respondWithEtag(req0, res, out)) return;
  res.json({ coupons: out, generatedAt: new Date().toISOString() });
});

app.get("/api/v1/recipes", (req0, res) => {
  let out = recipes;
  if (req0.query.section) out = out.filter((r) => r.section === req0.query.section);
  if (req0.query.query) {
    const q = String(req0.query.query).toLowerCase();
    out = out.filter(
      (r) =>
        r.name.toLowerCase().includes(q) ||
        r.tags.some((t) => t.includes(q)) ||
        r.ingredients.some((i) => i.name.toLowerCase().includes(q))
    );
  }
  if (respondWithEtag(req0, res, out)) return;
  res.json({ recipes: out, generatedAt: new Date().toISOString() });
});

app.get("/api/v1/catalog", (req0, res) => {
  let out = catalog;
  if (req0.query.query) {
    const q = String(req0.query.query).toLowerCase();
    out = out.filter((c) => c.name.toLowerCase().includes(q));
  }
  if (respondWithEtag(req0, res, out)) return;
  res.json({ catalog: out, generatedAt: new Date().toISOString() });
});

app.get("/api/v1/sync", asyncRoute(async (req0, res) => {
  const payload = await syncPayload();
  // Built per-request so the APK URL points at whatever host the app used.
  // Only advertise an update when a release APK is actually present to serve.
  // The request may arrive via a TLS-terminating proxy (e.g. cloudflared or a
  // reverse proxy), in which case req.protocol is still http. Detect the scheme
  // from x-forwarded-proto so the APK URL is https when the client used https.
  const scheme =
    req0.secure || String(req0.headers["x-forwarded-proto"] || "").split(",")[0].trim() === "https"
      ? "https"
      : "http";
  const update = APK_EXISTS()
    ? {
        versionName: UPDATE_VERSION_NAME,
        apkUrl: `${scheme}://${req0.get("host")}/releases/Thrive-release.apk`,
        notes: notesFor(UPDATE_VERSION_NAME),
      }
    : null;
  const body = { ...payload, update };
  if (respondWithEtag(req0, res, body)) return;
  res.json(body);
}));

// ---------------------------------------------------------------------------
// Anonymous state backup (favorites + pantry + budget)
// ---------------------------------------------------------------------------
// Free, account-less sync: each install generates an 8-character backup code
// (shown in Settings), and the app's state — saved deals, pantry items, and
// budget/shopping-list — is stored server-side under that code. Entering the
// same code on another phone merges everything across devices. The code is
// the only credential — same trust model as a URL slug.
//
// PUT merges per-section: only the sections present in the body replace the
// stored copy, so an older app version pushing favorites alone never wipes a
// device's pantry or budget.

const BACKUP_DIR = process.env.THRIVE_BACKUP_DIR || path.join(__dirname, "data", "backups");
const BACKUP_CODE_RE = /^[a-z0-9]{6,12}$/;
const BACKUP_MAX_ITEMS = 500;

function backupFile(code) {
  return path.join(BACKUP_DIR, `${code}.json`);
}

const EMPTY_BACKUP = { favorites: [], pantry: [], budget: null, updatedAt: null, revision: null };

/** Reads a backup file. Never throws: corrupt/absent files read as an empty backup. */
async function readBackupFile(code) {
  try {
    const saved = JSON.parse(await fsp.readFile(backupFile(code), "utf-8"));
    return {
      payload: saved,
      revision: typeof saved.revision === "string" && saved.revision.length > 0 ? saved.revision : null,
    };
  } catch {
    return { payload: null, revision: null };
  }
}

/** Writes a backup atomically: temp file in the same directory, then rename. */
async function writeBackupAtomic(code, payload) {
  await fsp.mkdir(BACKUP_DIR, { recursive: true });
  const tmp = path.join(BACKUP_DIR, `${code}.${process.pid}.${crypto.randomBytes(4).toString("hex")}.tmp`);
  await fsp.writeFile(tmp, JSON.stringify(payload, null, 2));
  await fsp.rename(tmp, backupFile(code));
}

/** Serializes writes per backup code so concurrent PUTs never interleave. */
const backupQueues = new Map();
function serializeBackup(code, fn) {
  const prev = backupQueues.get(code) || Promise.resolve();
  const tail = prev.catch(() => {});
  const result = tail.then(fn);
  const nextTail = result.catch(() => {}); // never rejects — safe queue tail
  backupQueues.set(code, nextTail);
  nextTail.finally(() => {
    if (backupQueues.get(code) === nextTail) backupQueues.delete(code);
  });
  return result;
}

function sanitizeFavorites(raw) {
  return [...new Set(raw)]
    .filter((f) => typeof f === "string" && f.length >= 1 && f.length <= 64)
    .slice(0, BACKUP_MAX_ITEMS);
}

function sanitizePantry(raw) {
  return raw
    .filter((o) => o && typeof o === "object")
    .map((o) => ({
      id: String(o.id || "").slice(0, 64),
      name: String(o.name || "").slice(0, 120),
      category: String(o.category || "").slice(0, 40),
      location: String(o.location || "").slice(0, 20),
      quantity: Number.isFinite(Number(o.quantity)) ? Math.max(0, Math.floor(Number(o.quantity))) : 1,
      unit: String(o.unit || "").slice(0, 24),
      expiresAt: o.expiresAt == null ? null : (Number.isFinite(Number(o.expiresAt)) ? Number(o.expiresAt) : null),
      addedAt: Number.isFinite(Number(o.addedAt)) ? Number(o.addedAt) : 0,
    }))
    .slice(0, BACKUP_MAX_ITEMS);
}

function sanitizeBudget(raw) {
  if (!raw || typeof raw !== "object" || Array.isArray(raw)) return null;
  const items = Array.isArray(raw.items)
    ? raw.items
        .filter((o) => o && typeof o === "object")
        .map((o) => ({
          id: String(o.id || "").slice(0, 64),
          name: String(o.name || "").slice(0, 120),
          category: String(o.category || "").slice(0, 40),
          quantity: Number.isFinite(Number(o.quantity)) ? Math.max(0, Math.floor(Number(o.quantity))) : 1,
          unit: String(o.unit || "").slice(0, 24),
          estPrice: Number.isFinite(Number(o.estPrice)) ? Math.max(0, Number(o.estPrice)) : 0,
          checked: !!o.checked,
          brand: o.brand == null ? null : String(o.brand).slice(0, 60),
        }))
        .slice(0, BACKUP_MAX_ITEMS)
    : [];
  return {
    budget: Number.isFinite(Number(raw.budget)) ? Math.max(0, Number(raw.budget)) : 0,
    people: Number.isFinite(Number(raw.people)) ? Math.max(1, Math.min(12, Math.floor(Number(raw.people)))) : 1,
    items,
  };
}

app.get("/api/v1/backup/:code", asyncRoute(async (req0, res) => {
  const code = String(req0.params.code || "").toLowerCase();
  if (!BACKUP_CODE_RE.test(code)) {
    return res.status(400).json({ error: "invalid backup code" });
  }
  const { payload, revision } = await readBackupFile(code);
  const body = payload
    ? {
        favorites: Array.isArray(payload.favorites) ? payload.favorites : [],
        pantry: Array.isArray(payload.pantry) ? payload.pantry : [],
        budget: payload.budget && typeof payload.budget === "object" ? payload.budget : null,
        updatedAt: typeof payload.updatedAt === "string" ? payload.updatedAt : null,
        revision,
      }
    : EMPTY_BACKUP;
  if (respondWithEtag(req0, res, body)) return;
  res.json(body);
}));

// Optimistic concurrency for backups: every PUT must carry If-Match with the
// revision from the last GET (or "*" to create). On conflict (409) the client
// re-pulls, re-merges, and retries — two devices can never silently overwrite
// each other. Writes are serialized per code and landed atomically.
app.put("/api/v1/backup/:code", asyncRoute(async (req0, res) => {
  const code = String(req0.params.code || "").toLowerCase();
  if (!BACKUP_CODE_RE.test(code)) {
    return res.status(400).json({ error: "invalid backup code" });
  }
  const body = req0.body && typeof req0.body === "object" && !Array.isArray(req0.body) ? req0.body : {};
  const hasFavorites = Array.isArray(body.favorites);
  const hasPantry = Array.isArray(body.pantry);
  const hasBudget = body.budget !== undefined;
  if (!hasFavorites && !hasPantry && !hasBudget) {
    return res.status(400).json({ error: "body must include favorites, pantry, or budget" });
  }
  const ifMatch = req0.get("if-match");
  if (ifMatch === undefined) {
    return res.status(428).json({ error: "If-Match header required (use \"*\" to create)" });
  }

  const payload = await serializeBackup(code, async () => {
    const current = await readBackupFile(code);
    if (ifMatch === "*") {
      if (current.revision !== null) {
        const err = new Error("backup already exists — re-pull with the current revision");
        err.status = 409;
        err.expose = true;
        err.currentRevision = current.revision;
        throw err;
      }
    } else if (current.revision === null) {
      const err = new Error("no backup exists for this code — use If-Match: * to create");
      err.status = 404;
      err.expose = true;
      throw err;
    } else if (current.revision !== ifMatch) {
      const err = new Error("conflict — the backup changed since you read it");
      err.status = 409;
      err.expose = true;
      err.currentRevision = current.revision;
      throw err;
    }

    const saved = current.payload || EMPTY_BACKUP;
    const next = {
      favorites: hasFavorites ? sanitizeFavorites(body.favorites) : (Array.isArray(saved.favorites) ? saved.favorites : []),
      pantry: hasPantry ? sanitizePantry(body.pantry) : (Array.isArray(saved.pantry) ? saved.pantry : []),
      budget: hasBudget ? sanitizeBudget(body.budget) : (saved.budget && typeof saved.budget === "object" ? saved.budget : null),
    };
    const stored = {
      ...next,
      updatedAt: new Date().toISOString(),
      revision: crypto.randomBytes(8).toString("hex"),
    };
    await writeBackupAtomic(code, stored);
    return stored;
  });

  res.json({
    ok: true,
    favorites: payload.favorites.length,
    pantry: payload.pantry.length,
    budget: payload.budget ? payload.budget.items.length : 0,
    updatedAt: payload.updatedAt,
    revision: payload.revision,
  });
}));

// Manual override: POST a deals array to preview a custom feed. The server may
// be reachable over a public tunnel, so this write route is guarded by a shared
// admin token (THRIVE_ADMIN_TOKEN). Without a token configured, the route is
// disabled entirely — the app never POSTs, so nothing legitimately breaks.
// The payload is validated STRICTLY and atomically: any invalid element rejects
// the whole payload with 400, so malformed data can never reach the cache or
// crash a later reader.
const ADMIN_TOKEN = process.env.THRIVE_ADMIN_TOKEN || null;

const DEAL_TEXT_LIMITS = { id: 64, store: 80, productName: 160, category: 40, unitPrice: 32, url: 2048, imageUrl: 2048, size: 40, brand: 60 };
const MAX_DEALS_PER_PAYLOAD = 5000;

function validateDeal(d, idx) {
  const errors = [];
  const err = (msg) => errors.push(`deal[${idx}]: ${msg}`);
  if (!d || typeof d !== "object" || Array.isArray(d)) {
    err("must be an object");
    return errors;
  }
  const str = (v, max) => (typeof v === "string" ? v.trim() : "");
  if (!str(d.id, DEAL_TEXT_LIMITS.id)) err("missing id");
  else if (str(d.id).length > DEAL_TEXT_LIMITS.id) err("id too long");
  if (!str(d.store, DEAL_TEXT_LIMITS.store)) err("missing store");
  else if (str(d.store).length > DEAL_TEXT_LIMITS.store) err("store too long");
  if (!str(d.productName, DEAL_TEXT_LIMITS.productName)) err("missing productName");
  else if (str(d.productName).length > DEAL_TEXT_LIMITS.productName) err("productName too long");
  const category = str(d.category, DEAL_TEXT_LIMITS.category);
  if (!category) err("missing category");
  else if (category.length > DEAL_TEXT_LIMITS.category) err("category too long");
  else if (/[\u0000-\u001f\u007f]/.test(category)) err("category contains control characters");
  if (typeof d.price !== "number" || !Number.isFinite(d.price) || d.price < 0 || d.price > 1e6) {
    err("price must be a finite number in [0, 1000000]");
  }
  if (d.savingsPercent !== undefined && (typeof d.savingsPercent !== "number" || !Number.isInteger(d.savingsPercent) || d.savingsPercent < 0 || d.savingsPercent > 100)) {
    err("savingsPercent must be an integer in [0, 100]");
  }
  if (d.endsInDays !== undefined && (typeof d.endsInDays !== "number" || !Number.isInteger(d.endsInDays) || d.endsInDays < 0 || d.endsInDays > 365)) {
    err("endsInDays must be an integer in [0, 365]");
  }
  if (d.keywords !== undefined && (!Array.isArray(d.keywords) || d.keywords.length > 50 || d.keywords.some((k) => typeof k !== "string" || k.length > 60))) {
    err("keywords must be an array of at most 50 short strings");
  }
  if (d.unitPrice !== undefined && typeof d.unitPrice !== "string") err("unitPrice must be a string");
  for (const field of ["url", "imageUrl"]) {
    if (d[field] !== undefined && d[field] !== null) {
      const u = str(d[field], DEAL_TEXT_LIMITS[field]);
      if (u && u.length > DEAL_TEXT_LIMITS[field]) err(`${field} too long`);
      else if (u && !/^https?:\/\/[^\s]+$/i.test(u)) err(`${field} must be an http(s) URL`);
    }
  }
  return errors;
}

app.post("/api/v1/deals", (req0, res) => {
  if (!ADMIN_TOKEN) {
    return res.status(403).json({ error: "admin override disabled (set THRIVE_ADMIN_TOKEN to enable)" });
  }
  const provided = req0.get("x-thrive-admin-token");
  if (!provided || provided !== ADMIN_TOKEN) {
    return res.status(401).json({ error: "unauthorized" });
  }
  const body = req0.body;
  if (!Array.isArray(body)) {
    return res.status(400).json({ error: "body must be an array of deals" });
  }
  if (body.length > MAX_DEALS_PER_PAYLOAD) {
    return res.status(400).json({ error: `too many deals (max ${MAX_DEALS_PER_PAYLOAD})` });
  }
  const errors = [];
  body.forEach((d, i) => errors.push(...validateDeal(d, i)));
  if (errors.length) {
    return res.status(400).json({ error: "invalid deals payload", errors: errors.slice(0, 50) });
  }
  // Reject duplicate ids so the feed stays keyable.
  const ids = new Set();
  for (const d of body) {
    if (ids.has(d.id)) {
      return res.status(400).json({ error: `duplicate deal id: ${d.id}` });
    }
    ids.add(d.id);
  }
  // Normalize to the canonical Deal shape.
  dealsCache = body.map((d) => ({
    id: String(d.id).trim(),
    store: String(d.store).trim(),
    productName: String(d.productName).trim(),
    category: String(d.category).trim(),
    price: d.price,
    unitPrice: typeof d.unitPrice === "string" ? d.unitPrice : "",
    savingsPercent: Number.isInteger(d.savingsPercent) ? d.savingsPercent : 0,
    keywords: Array.isArray(d.keywords) ? d.keywords.map((k) => String(k)) : [],
    endsInDays: Number.isInteger(d.endsInDays) ? d.endsInDays : 7,
    url: typeof d.url === "string" && d.url ? d.url : null,
    urlVerified: !!d.urlVerified,
    size: typeof d.size === "string" && d.size ? d.size : null,
    brand: typeof d.brand === "string" && d.brand ? d.brand : null,
    imageUrl: typeof d.imageUrl === "string" && d.imageUrl ? d.imageUrl : null,
    estimated: d.estimated !== false,
  }));
  dealsCacheAt = new Date().toISOString().slice(0, 10);
  payloadCache = null;
  res.json({ ok: true, deals: dealsCache.length });
});

// ---------------------------------------------------------------------------
// Central error handler (registered last so it catches every route).
// ---------------------------------------------------------------------------
app.use((err, req, res, next) => {
  if (res.headersSent) return next(err);
  const isBodyError = err.type === "entity.parse.failed" || err.type === "entity.too.large";
  const status = err.status || err.statusCode || (isBodyError ? 400 : 500);
  if (status >= 500) {
    console.error(`[error] ${req.method} ${req.originalUrl} -> ${status}: ${err.message}`);
  }
  const body = {
    error: status >= 500 ? "internal error" : err.expose || isBodyError ? err.message : "bad request",
  };
  if (err.currentRevision) body.currentRevision = err.currentRevision;
  res.status(status).json(body);
});

process.on("unhandledRejection", (reason) => {
  console.error("[unhandledRejection]", reason && reason.stack ? reason.stack : reason);
});
process.on("uncaughtException", (err) => {
  console.error("[uncaughtException]", err && err.stack ? err.stack : err);
});

if (require.main === module) {
  app.listen(PORT, () => {
    console.log(`Thrive sync API listening on http://localhost:${PORT}`);
    console.log(`Sources: ${sources.map((s) => s.name).join(", ")}`);
    console.log(`  GET /api/v1/health`);
    console.log(`  GET /api/v1/sync   (ETag-cached full payload)`);
    console.log(`  GET /api/v1/deals | /coupons | /recipes | /catalog`);
  });
}

module.exports = app;
