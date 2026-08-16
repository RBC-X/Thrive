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

/**
 * Converts a live Deal (e.g. a Kroger product with a verified product-page
 * URL) into the Coupon shape the Savings feed expects. Live deals are honest
 * by construction: urlVerified=true, real before-price when a promo is live,
 * and the API's own product photo when one exists.
 */
function dealToCoupon(d) {
  const before = d.regularPrice && d.regularPrice > d.price
    ? d.regularPrice
    : d.savingsPercent > 0 && d.savingsPercent < 100
      ? d.price / (1 - d.savingsPercent / 100)
      : d.price;
  // Only offers that are genuinely on sale or carry a coupon belong in the
  // Savings feed. A product that is merely "in the catalog" at regular price
  // is not a deal — drop it rather than showing a live price with no savings.
  if (!(before > d.price)) return null;
  return {
    id: d.id,
    store: d.store,
    title: d.productName,
    description: d.productName + (d.size ? `, ${d.size}` : ""),
    category: d.category || "Grocery",
    priceBefore: Math.round(before * 100) / 100,
    priceAfter: Math.round(d.price * 100) / 100,
    dealType: "LINK",
    code: null,
    url: d.url || null,
    urlVerified: !!d.urlVerified,
    brand: d.brand || null,
    endsInDays: Number.isInteger(d.endsInDays) ? d.endsInDays : 7,
    isNew: true,
    terms: "Live price from the retailer API — opens the exact product page.",
    imageSeed: null,
    imageUrl: d.imageUrl || null,
    storeLogoUrl: null,
    estimated: !!d.estimated,
  };
}

/**
 * The Savings feed: live deals with verified product links first (they are
 * real, current, and open the exact product page), then the bundled catalog.
 * The app itself shows only urlVerified offers as available — this ordering
 * simply makes the live catalog the first thing a synced phone sees.
 */
function couponsFor(deals) {
  // The full bundled catalog (5,000+ offers across 45 retailers, daily-rotated
  // new/expiry flags) is the floor of the Savings feed. Live verified deals
  // from retailer APIs are prepended — never a replacement — so a synced phone
  // keeps every category (Grocery, Tech, Beauty, …) at full strength instead
  // of shrinking to whatever the live cache happens to hold. Every offer in
  // both halves already obeys the house rules: real discount (priceBefore >
  // priceAfter) and a real destination URL; the app re-checks both.
  const bundled = rotateCoupons(daySeed()).filter((c) => c && c.url);
  const live = Array.isArray(deals)
    ? deals
        .filter((d) => d && d.urlVerified)
        .map(dealToCoupon)
        .filter((c) => c !== null) // keep only real promos (dealToCoupon drops non-sale items)
    : [];
  if (!live.length) return bundled;
  // Live ids override their bundled twins (a fresher price beats the snapshot);
  // everything else keeps the bundled entry, so no offer is ever dropped.
  const liveIds = new Set(live.map((c) => c.id).filter(Boolean));
  return [...live, ...bundled.filter((c) => !liveIds.has(c.id))];
}

const VERSION = 4;

let dealsCache = null;
let dealsCacheAt = 0;
let payloadCache = null;
let payloadCacheAt = 0;

// ---------------------------------------------------------------------------
// Nearby-deals support
// ---------------------------------------------------------------------------
// When the app shares an approximate location (coarse permission, opt-in), the
// feed annotates every deal with the distance to the user's nearest branch of
// that store chain and sorts nearest-first. Kroger live deals carry the exact
// resolved store coordinates from the Kroger Locations API; curated chains use
// this registry of representative branch coordinates across major metros. The
// distance is an honest estimate ("~3 mi to nearest branch"), not a promise of
// the exact store's stock.

const STORES = {
  Walmart: [
    { city: "Seattle", lat: 47.62, lng: -122.33 }, { city: "Denver", lat: 39.74, lng: -104.99 },
    { city: "Chicago", lat: 41.88, lng: -87.63 }, { city: "Atlanta", lat: 33.75, lng: -84.39 },
    { city: "Phoenix", lat: 33.45, lng: -112.07 }, { city: "Boston", lat: 42.36, lng: -71.06 },
    { city: "Dallas", lat: 32.78, lng: -96.80 }, { city: "Orlando", lat: 28.54, lng: -81.38 },
  ],
  Kroger: [
    { city: "Cincinnati", lat: 39.10, lng: -84.51 }, { city: "Atlanta", lat: 33.77, lng: -84.39 },
    { city: "Houston", lat: 29.76, lng: -95.37 }, { city: "Columbus", lat: 39.96, lng: -82.99 },
    { city: "Nashville", lat: 36.16, lng: -86.78 }, { city: "Louisville", lat: 38.25, lng: -85.76 },
    { city: "Indianapolis", lat: 39.77, lng: -86.16 }, { city: "Denver", lat: 39.74, lng: -104.99 },
  ],
  Target: [
    { city: "Minneapolis", lat: 44.98, lng: -93.27 }, { city: "Chicago", lat: 41.88, lng: -87.63 },
    { city: "Dallas", lat: 32.78, lng: -96.80 }, { city: "San Diego", lat: 32.72, lng: -117.16 },
    { city: "Miami", lat: 25.76, lng: -80.19 }, { city: "Portland", lat: 45.52, lng: -122.68 },
  ],
  Costco: [
    { city: "Seattle", lat: 47.62, lng: -122.33 }, { city: "Sacramento", lat: 38.58, lng: -121.49 },
    { city: "Chicago", lat: 41.88, lng: -87.63 }, { city: "Atlanta", lat: 33.75, lng: -84.39 },
    { city: "Houston", lat: 29.76, lng: -95.37 }, { city: "Boston", lat: 42.36, lng: -71.06 },
  ],
  Aldi: [
    { city: "Chicago", lat: 41.88, lng: -87.63 }, { city: "St. Louis", lat: 38.63, lng: -90.20 },
    { city: "Cincinnati", lat: 39.10, lng: -84.51 }, { city: "Charlotte", lat: 35.23, lng: -80.84 },
    { city: "Tampa", lat: 27.95, lng: -82.46 }, { city: "Kansas City", lat: 39.10, lng: -94.58 },
  ],
  Publix: [
    { city: "Miami", lat: 25.76, lng: -80.19 }, { city: "Tampa", lat: 27.95, lng: -82.46 },
    { city: "Orlando", lat: 28.54, lng: -81.38 }, { city: "Atlanta", lat: 33.75, lng: -84.39 },
    { city: "Charlotte", lat: 35.23, lng: -80.84 }, { city: "Jacksonville", lat: 30.33, lng: -81.66 },
  ],
  "Dollar General": [
    { city: "Nashville", lat: 36.16, lng: -86.78 }, { city: "Birmingham", lat: 33.52, lng: -86.81 },
    { city: "Columbus", lat: 39.96, lng: -82.99 }, { city: "Memphis", lat: 35.15, lng: -90.05 },
  ],
  CVS: [
    { city: "Boston", lat: 42.36, lng: -71.06 }, { city: "Phoenix", lat: 33.45, lng: -112.07 },
    { city: "Chicago", lat: 41.88, lng: -87.63 }, { city: "Dallas", lat: 32.78, lng: -96.80 },
  ],
  Walgreens: [
    { city: "Chicago", lat: 41.88, lng: -87.63 }, { city: "Denver", lat: 39.74, lng: -104.99 },
    { city: "San Diego", lat: 32.72, lng: -117.16 }, { city: "Houston", lat: 29.76, lng: -95.37 },
  ],
  "Whole Foods": [
    { city: "Austin", lat: 30.27, lng: -97.74 }, { city: "Boston", lat: 42.36, lng: -71.06 },
    { city: "Chicago", lat: 41.88, lng: -87.63 }, { city: "San Francisco", lat: 37.77, lng: -122.42 },
  ],
  "Trader Joe's": [
    { city: "Los Angeles", lat: 34.05, lng: -118.24 }, { city: "Portland", lat: 45.52, lng: -122.68 },
    { city: "New York", lat: 40.71, lng: -74.01 }, { city: "Chicago", lat: 41.88, lng: -87.63 },
  ],
};

const R_EARTH_MI = 3958.8;

function haversineMi(lat1, lng1, lat2, lng2) {
  const toRad = (d) => (d * Math.PI) / 180;
  const dLat = toRad(lat2 - lat1);
  const dLng = toRad(lng2 - lng1);
  const a =
    Math.sin(dLat / 2) ** 2 +
    Math.cos(toRad(lat1)) * Math.cos(toRad(lat2)) * Math.sin(dLng / 2) ** 2;
  return 2 * R_EARTH_MI * Math.asin(Math.sqrt(a));
}

/** Nearest branch of [storeName] to (lat, lng) from the registry, or null. */
function nearestBranch(storeName, lat, lng) {
  const chain = STORES[String(storeName || "")];
  if (!chain) return null;
  let best = null;
  for (const b of chain) {
    const d = haversineMi(lat, lng, b.lat, b.lng);
    if (!best || d < best.distMi) best = { ...b, distMi: d };
  }
  return best;
}

/**
 * Annotates deals with storeDistanceMi (nearest branch) and returns them
 * sorted nearest-first. Kroger live deals already carry the exact store's
 * coordinates; everything else uses the registry. Deals with no store match
 * sort last (they stay visible, just unranked).
 */
function annotateNearby(lat, lng, deals) {
  return deals
    .map((d) => {
      let distMi = null;
      let branch = null;
      if (d.storeLat != null && d.storeLng != null) {
        distMi = haversineMi(lat, lng, d.storeLat, d.storeLng);
      } else {
        branch = nearestBranch(d.store, lat, lng);
        if (branch) distMi = branch.distMi;
      }
      const out = { ...d, storeDistanceMi: distMi != null ? Math.round(distMi * 10) / 10 : null };
      if (branch) out.storeCity = branch.city;
      return out;
    })
    .sort((a, b) => {
      if (a.storeDistanceMi == null && b.storeDistanceMi == null) return 0;
      if (a.storeDistanceMi == null) return 1;
      if (b.storeDistanceMi == null) return -1;
      return a.storeDistanceMi - b.storeDistanceMi;
    });
}

/** Top store chains by nearest branch, for a "stores near you" summary. */
function nearbyStores(lat, lng) {
  const rows = [];
  for (const chain of Object.keys(STORES)) {
    const b = nearestBranch(chain, lat, lng);
    if (b) rows.push({ store: chain, city: b.city, distMi: Math.round(b.distMi * 10) / 10, lat: b.lat, lng: b.lng });
  }
  return rows.sort((a, b) => a.distMi - b.distMi).slice(0, 6);
}

/** Strict lat/lng query parsing — garbage in is a 400, never NaN propagation. */
function parseCoord(raw, name, lo, hi) {
  if (raw === undefined) return null;
  const s = String(raw);
  // Real GPS fixes carry 10-14 decimals — accept full precision, reject junk
  // (letters, exponents, empty) via the numeric + range checks below.
  if (!/^-?\d{1,3}(\.\d{1,15})?$/.test(s)) {
    const err = new Error(`${name} must be a decimal number`);
    err.status = 400;
    err.expose = true;
    throw err;
  }
  const n = Number(s);
  if (!Number.isFinite(n) || n < lo || n > hi) {
    const err = new Error(`${name} must be between ${lo} and ${hi}`);
    err.status = 400;
    err.expose = true;
    throw err;
  }
  return n;
}

/** Require both coords or neither — a lone lat/lng is a broken client, 400. */
function parseLocation(query) {
  const lat = parseCoord(query.lat, "lat", -90, 90);
  const lng = parseCoord(query.lng, "lng", -180, 180);
  if ((lat == null) !== (lng == null)) {
    const err = new Error("lat and lng must be provided together");
    err.status = 400;
    err.expose = true;
    throw err;
  }
  return [lat, lng];
}

/** Buckets coordinates (~1/100° ≈ 1 km) so nearby users share a cached payload. */
function locBucket(lat, lng) {
  return `${Math.round(lat * 100)},${Math.round(lng * 100)}`;
}

const locCache = new Map(); // location-aware deal/payload cache (bounded)

async function getDeals(lat, lng) {
  const todayKey = new Date().toISOString().slice(0, 10);
  const withLoc = lat != null && lng != null;
  if (!withLoc && dealsCache && dealsCacheAt === todayKey) return dealsCache;
  const key = withLoc ? `deal:${locBucket(lat, lng)}` : null;
  if (withLoc) {
    const hit = locCache.get(key);
    if (hit && hit.at === todayKey) return hit.deals;
  }
  const merged = [];
  for (const source of sources) {
    try {
      const deals = withLoc ? await source.deals(lat, lng) : await source.deals();
      if (Array.isArray(deals)) merged.push(...deals);
    } catch (err) {
      console.error(`[source:${source.name}] ${err.message}`);
    }
  }
  const out = merged.length ? merged : loadJson("deals.json");
  if (withLoc) {
    locCache.set(key, { at: todayKey, deals: out });
    if (locCache.size > 40) locCache.delete(locCache.keys().next().value); // bound memory
    return out;
  }
  dealsCache = out;
  dealsCacheAt = todayKey;
  payloadCache = null; // force payload rebuild with a fresh timestamp
  return dealsCache;
}

async function syncPayload(lat, lng) {
  const todayKey = new Date().toISOString().slice(0, 10);
  const withLoc = lat != null && lng != null;
  if (!withLoc && payloadCache && payloadCacheAt === todayKey) return payloadCache;
  const pKey = withLoc ? `payload:${locBucket(lat, lng)}` : null;
  if (withLoc) {
    const hit = locCache.get(pKey);
    if (hit && hit.at === todayKey) return hit.deals;
  }
  const deals = await getDeals(lat, lng);
  const location = withLoc
    ? { lat, lng, nearbyStores: nearbyStores(lat, lng) }
    : null;
  payloadCache = {
    version: VERSION,
    generatedAt: new Date().toISOString(),
    source: sources.map((s) => s.name),
    location,
    deals: withLoc ? annotateNearby(lat, lng, deals) : deals,
    coupons: couponsFor(deals),
    recipes,
    catalog,
  };
  payloadCacheAt = todayKey;
  if (withLoc) locCache.set(pKey, { at: todayKey, deals: payloadCache });
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
  const [lat, lng] = parseLocation(req0.query);
  const deals = await getDeals(lat, lng);
  const category = parseCategory(req0.query.category);
  const limit = parseLimit(req0.query.limit);
  let out = lat != null && lng != null ? annotateNearby(lat, lng, deals) : deals;
  if (category) {
    // Defensive: never assume a source/deal has a string category.
    out = out.filter((d) => d && typeof d.category === "string" && d.category.toLowerCase() === category);
  }
  if (limit !== null) out = out.slice(0, limit);
  if (respondWithEtag(req0, res, out)) return;
  res.json({ deals: out, generatedAt: new Date().toISOString() });
}));

app.get("/api/v1/coupons", asyncRoute(async (req0, res) => {
  const category = parseCategory(req0.query.category);
  const limit = parseLimit(req0.query.limit);
  const [lat, lng] = parseLocation(req0.query);
  const deals = await getDeals(lat, lng);
  let out = couponsFor(deals);
  if (category) {
    out = out.filter((c) => c && typeof c.category === "string" && c.category.toLowerCase() === category);
  }
  if (limit !== null) out = out.slice(0, limit);
  if (respondWithEtag(req0, res, out)) return;
  res.json({ coupons: out, generatedAt: new Date().toISOString() });
}));

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
  const [lat, lng] = parseLocation(req0.query);
  const payload = await syncPayload(lat, lng);
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

// ---------------------------------------------------------------------------
// Backup route internals (shared by code-keyed and Google-account backups).
// ---------------------------------------------------------------------------

/** GET handler body shared by /api/v1/backup/:code and account backups. */
async function serveBackupGet(req0, res, key) {
  const { payload, revision } = await readBackupFile(key);
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
}

/**
 * PUT handler body shared by /api/v1/backup/:code and account backups. Uses
 * the same optimistic concurrency (If-Match revision), per-key serialization,
 * and atomic rename as the code routes; the Google-account variant simply
 * passes the derived account key.
 */
async function serveBackupPut(req0, res, key) {
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

  const payload = await serializeBackup(key, async () => {
    const current = await readBackupFile(key);
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
    await writeBackupAtomic(key, stored);
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
}

app.get("/api/v1/backup/:code", asyncRoute(async (req0, res) => {
  const code = String(req0.params.code || "").toLowerCase();
  if (!BACKUP_CODE_RE.test(code)) {
    return res.status(400).json({ error: "invalid backup code" });
  }
  return serveBackupGet(req0, res, code);
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
  return serveBackupPut(req0, res, code);
}));

// ---------------------------------------------------------------------------
// Google Sign-In backup (favorites + pantry + budget under a Google account)
// ---------------------------------------------------------------------------
// The app signs in with Google and sends the ID token here; the server
// verifies it with Google's public tokeninfo endpoint (no API key or secret
// needed server-side) and derives a stable, opaque storage key from the
// account's `sub`. State is then stored/read under that key using the exact
// same atomic + optimistic-concurrency machinery as code backups, so the
// feature is never weaker than the code path. The ID token is never stored
// and never appears in URLs or logs.
//
// THRIVE_GOOGLE_CLIENT_ID: the OAuth "Web" client ID this backend accepts
// (audience check). When unset the server accepts any valid Google ID token
// — fine for a private tunnel, but set it in production so only your app's
// token audience is accepted.

const GOOGLE_CLIENT_ID = process.env.THRIVE_GOOGLE_CLIENT_ID || null;

// Test-only: when set, token verification is short-circuited to a fake account
// so the whole flow (auth, account-keyed backup, concurrency) can be tested
// without network access to Google. Never set in production.
const GOOGLE_TEST_SUB = process.env.THRIVE_GOOGLE_TEST_SUB || null;

/**
 * Verifies a Google ID token with Google's tokeninfo endpoint and returns the
 * verified account { sub, name, email, picture }. Throws with err.status 401
 * on any invalid/expired/tampered token; the caller maps that to a 401.
 */
async function verifyGoogleIdToken(idToken) {
  if (GOOGLE_TEST_SUB) {
    return { sub: GOOGLE_TEST_SUB, name: "Test User", email: "test@example.com", picture: null };
  }
  if (typeof idToken !== "string" || idToken.length < 20 || idToken.length > 4096) {
    const err = new Error("missing or malformed idToken");
    err.status = 401;
    throw err;
  }
  const url = `https://oauth2.googleapis.com/tokeninfo?id_token=${encodeURIComponent(idToken)}`;
  const resp = await fetch(url, { signal: AbortSignal.timeout(10000) });
  if (!resp.ok) {
    const err = new Error("Google rejected the id token");
    err.status = 401;
    throw err;
  }
  const info = await resp.json();
  if (!info || typeof info.sub !== "string" || info.sub.length === 0 || info.sub.length > 64) {
    const err = new Error("tokeninfo returned no valid subject");
    err.status = 401;
    throw err;
  }
  if (GOOGLE_CLIENT_ID && info.aud !== GOOGLE_CLIENT_ID) {
    const err = new Error("id token audience does not match this server's client id");
    err.status = 401;
    throw err;
  }
  return {
    sub: info.sub,
    name: typeof info.name === "string" ? info.name : null,
    email: typeof info.email === "string" ? info.email : null,
    picture: typeof info.picture === "string" ? info.picture : null,
  };
}

/** Stable opaque storage key for a Google account (never the raw sub). */
function googleAccountKey(sub) {
  return "g" + crypto.createHash("sha256").update("thrive:" + sub).digest("hex").slice(0, 15);
}

/** Extracts + verifies the Bearer Google ID token from a request. */
async function googleAccountFrom(req) {
  const header = req.get("authorization") || "";
  const m = header.match(/^Bearer\s+(.+)$/i);
  if (!m) {
    const err = new Error("Authorization: Bearer <google id token> required");
    err.status = 401;
    throw err;
  }
  const account = await verifyGoogleIdToken(m[1].trim());
  return account;
}

// Returns the account profile + storage key so the app can show who is signed
// in and keep the same key across devices.
app.post("/api/v1/auth/google", asyncRoute(async (req0, res) => {
  const idToken = req0.body && typeof req0.body.idToken === "string" ? req0.body.idToken : "";
  if (idToken.length < 20 || idToken.length > 4096) {
    const err = new Error("missing or malformed idToken");
    err.status = 401;
    throw err;
  }
  const account = await verifyGoogleIdToken(idToken);
  res.json({
    ok: true,
    sub: account.sub,
    name: account.name,
    email: account.email,
    picture: account.picture,
    accountKey: googleAccountKey(account.sub),
  });
}));

app.get("/api/v1/account/backup", asyncRoute(async (req0, res) => {
  const account = await googleAccountFrom(req0);
  return serveBackupGet(req0, res, googleAccountKey(account.sub));
}));

app.put("/api/v1/account/backup", asyncRoute(async (req0, res) => {
  const account = await googleAccountFrom(req0);
  return serveBackupPut(req0, res, googleAccountKey(account.sub));
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
    regularPrice: Number.isFinite(d.regularPrice) && d.regularPrice > 0 ? Math.round(d.regularPrice * 100) / 100 : null,
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
    storeDistanceMi: Number.isFinite(d.storeDistanceMi) ? Math.round(d.storeDistanceMi * 10) / 10 : null,
    storeCity: typeof d.storeCity === "string" && d.storeCity ? d.storeCity : null,
    storeLat: Number.isFinite(d.storeLat) ? d.storeLat : null,
    storeLng: Number.isFinite(d.storeLng) ? d.storeLng : null,
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
  // Warm the deal cache in the background so the first phone sync of the day
  // is fast (a cold cache pulls hundreds of live retailer terms and can take
  // ~15-20s — warming it at boot makes the first request snappy).
  getDeals().then(() => console.log("[warmup] deal cache ready")).catch((e) => console.error(`[warmup] ${e.message}`));
}

module.exports = app;
