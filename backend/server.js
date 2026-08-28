"use strict";

require("./dotenv"); // loads backend/.env so credentials can live in a file

const express = require("express");
const cors = require("cors");
const crypto = require("crypto");
const fs = require("fs");
const fsp = require("fs/promises");
const path = require("path");
const { DailyRotationSource, PartnerApiSource, KrogerLiveSource, TargetLiveSource } = require("./src/sources");
const { ExaService } = require("./src/exaService");
const { AccountStore } = require("./src/accountStore");

const app = express();
app.disable("x-powered-by");
// The public service is reached through one cloudflared process on loopback.
// Trust forwarded client addresses only from that local hop; never trust an
// arbitrary remote proxy/header. This keeps per-client rate limiting from
// collapsing every tunnel user into 127.0.0.1.
app.set("trust proxy", (ip) => ip === "127.0.0.1" || ip === "::1" || ip === "::ffff:127.0.0.1");
app.use((req, res, next) => {
  res.set({
    "X-Content-Type-Options": "nosniff",
    "X-Frame-Options": "DENY",
    "Referrer-Policy": "no-referrer",
    "Permissions-Policy": "camera=(), microphone=(), geolocation=()",
    "Cross-Origin-Resource-Policy": "same-site",
  });
  if (req.path.startsWith("/api/v1/auth/") || req.path.startsWith("/api/v1/account/")) {
    res.set("Cache-Control", "no-store");
  }
  next();
});
app.use(cors());
app.use(express.json({ limit: "2mb" }));

const PORT = Number(process.env.PORT || 4000) || 4000;
const HOST = process.env.HOST || "127.0.0.1";

// ---------------------------------------------------------------------------
// Error handling — malformed request data must NEVER terminate the process.
// Express 4 does not catch async rejections, so every async route is wrapped in
// asyncRoute(); anything that still escapes lands in the central error handler
// and the process-level guards below (which log and keep serving).
// ---------------------------------------------------------------------------
const asyncRoute = (fn) => (req, res, next) => Promise.resolve(fn(req, res, next)).catch(next);

function rateLimit({ windowMs, max }) {
  const buckets = new Map();
  return (req, res, next) => {
    const now = Date.now();
    const key = req.ip || req.socket.remoteAddress || "unknown";
    let bucket = buckets.get(key);
    if (!bucket || bucket.resetAt <= now) bucket = { count: 0, resetAt: now + windowMs };
    bucket.count += 1;
    buckets.set(key, bucket);
    res.set("RateLimit-Limit", String(max));
    res.set("RateLimit-Remaining", String(Math.max(0, max - bucket.count)));
    res.set("RateLimit-Reset", String(Math.ceil(bucket.resetAt / 1000)));
    if (bucket.count > max) {
      res.set("Retry-After", String(Math.max(1, Math.ceil((bucket.resetAt - now) / 1000))));
      return res.status(429).json({ error: "too many requests; try again later" });
    }
    if (buckets.size > 5000) {
      for (const [entryKey, entry] of buckets) if (entry.resetAt <= now) buckets.delete(entryKey);
    }
    next();
  };
}

const authRateLimit = rateLimit({ windowMs: 10 * 60 * 1000, max: 30 });
const accountRateLimit = rateLimit({ windowMs: 10 * 60 * 1000, max: 300 });

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

const sources = process.env.THRIVE_TEST_BUNDLED_ONLY === "1"
  ? [new DailyRotationSource()]
  : [new DailyRotationSource(), new PartnerApiSource(), new KrogerLiveSource(), new TargetLiveSource()]
      .filter((s) => s.enabled !== false);

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
 * The Savings feed prefers live deals with verified product links. When no
 * retailer API has a current promotion, it returns the bundled planning
 * estimates instead of an empty screen. Those estimates keep urlVerified=false
 * and estimated=true, so clients can never mistake a retailer search link for
 * a verified product page or a planning price for a live offer.
 */
function couponsFor(deals) {
  const bundledEstimates = rotateCoupons(daySeed()).filter(
    (c) => c && c.url && c.urlVerified !== true && c.estimated === true,
  );
  const live = Array.isArray(deals)
    ? deals
        .filter((d) => d && d.urlVerified)
        .map(dealToCoupon)
        .filter((c) => c !== null) // keep only real promos (dealToCoupon drops non-sale items)
    : [];
  return live.length ? live : bundledEstimates;
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
    if (b) rows.push({ store: chain, city: b.city, distMi: Math.round(b.distMi * 10) / 10 });
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
  // Location-aware and location-free payloads are cached SEPARATELY. A
  // location-tagged payload must never overwrite the shared location-free
  // cache (and vice versa) — otherwise the next request with a different
  // location mode would receive another user's `location` block and
  // distance-annotated deals.
  if (withLoc) {
    const pKey = `payload:${locBucket(lat, lng)}`;
    const hit = locCache.get(pKey);
    if (hit && hit.at === todayKey) return hit.deals;
  } else if (payloadCache && payloadCacheAt === todayKey) {
    return payloadCache;
  }
  const deals = await getDeals(lat, lng);
  // Never echo exact user coordinates. The server uses them transiently to
  // rank deals, but public responses contain only coarse store/city/distance
  // results. This prevents location leakage through logs, caches, or clients.
  const location = withLoc
    ? { nearbyStores: nearbyStores(lat, lng) }
    : null;
  const body = {
    version: VERSION,
    generatedAt: new Date().toISOString(),
    source: sources.map((s) => s.name),
    location,
    deals: withLoc ? annotateNearby(lat, lng, deals) : deals,
    coupons: couponsFor(deals),
    recipes,
    catalog,
  };
  if (withLoc) {
    locCache.set(`payload:${locBucket(lat, lng)}`, { at: todayKey, deals: body });
    if (locCache.size > 40) locCache.delete(locCache.keys().next().value); // bound memory
    return body;
  }
  payloadCache = body;
  payloadCacheAt = todayKey;
  return body;
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

// ---------------------------------------------------------------------------
// Web discovery (optional Exa search). Results are DISCOVERY LEADS, never
// verified prices/deals: every item is labeled verified:false / web-discovery
// and the app must treat it as a pointer to look, not a claim. When Exa is
// unconfigured, rate-limited, or failing, these return an honest empty state
// and every other Thrive feature keeps working untouched.
// ---------------------------------------------------------------------------
const exa = new ExaService();

function exaQuery(req) {
  const q = ExaService.validateQuery(req.query.q); // throws 400 with .status/.expose
  const limit = Number(req.query.limit);
  if (req.query.limit !== undefined && (!Number.isInteger(limit) || limit < 1 || limit > 8)) {
    const err = new Error("limit must be an integer between 1 and 8");
    err.status = 400;
    err.expose = true;
    throw err;
  }
  return { q, limit };
}

app.get("/api/v1/search/offers", asyncRoute(async (req, res) => {
  const { q, limit } = exaQuery(req);
  const out = await exa.search(q, { limit, kind: "offers" });
  res.json({
    ...out,
    label: "Web-discovered leads — not verified prices. Open the link to confirm the current offer.",
  });
}));

app.get("/api/v1/search/recipes", asyncRoute(async (req, res) => {
  const { q, limit } = exaQuery(req);
  const out = await exa.search(q, { limit, kind: "recipes" });
  res.json({
    ...out,
    label: "Web-discovered recipes — read the source before cooking.",
  });
}));

app.get("/api/v1/search/product", asyncRoute(async (req, res) => {
  const { q, limit } = exaQuery(req);
  const out = await exa.search(q, { limit, kind: "product" });
  res.json({
    ...out,
    label: "Web-discovered product pages — verify the exact product on the store's site.",
  });
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
const BACKUP_ENCRYPTION_KEY = (() => {
  const raw = process.env.THRIVE_BACKUP_ENCRYPTION_KEY;
  if (raw && /^[0-9a-f]{64}$/i.test(raw)) return Buffer.from(raw, "hex");
  if (process.env.NODE_ENV === "test" || process.env.THRIVE_TEST_BUNDLED_ONLY === "1" || process.env.THRIVE_GOOGLE_TEST_SUB) {
    return crypto.createHash("sha256").update("thrive-test-backup-key").digest();
  }
  throw new Error("THRIVE_BACKUP_ENCRYPTION_KEY must be a 64-character hex key in production");
})();

function encryptBackup(payload) {
  const iv = crypto.randomBytes(12);
  const cipher = crypto.createCipheriv("aes-256-gcm", BACKUP_ENCRYPTION_KEY, iv);
  const ciphertext = Buffer.concat([cipher.update(JSON.stringify(payload), "utf8"), cipher.final()]);
  return JSON.stringify({ v: 1, alg: "aes-256-gcm", iv: iv.toString("base64url"), tag: cipher.getAuthTag().toString("base64url"), data: ciphertext.toString("base64url") });
}

function decryptBackup(raw) {
  const envelope = JSON.parse(raw);
  if (!envelope || envelope.v !== 1 || envelope.alg !== "aes-256-gcm") throw new Error("unsupported backup envelope");
  const decipher = crypto.createDecipheriv("aes-256-gcm", BACKUP_ENCRYPTION_KEY, Buffer.from(envelope.iv, "base64url"));
  decipher.setAuthTag(Buffer.from(envelope.tag, "base64url"));
  const plaintext = Buffer.concat([decipher.update(Buffer.from(envelope.data, "base64url")), decipher.final()]);
  return JSON.parse(plaintext.toString("utf8"));
}
const BACKUP_MAX_ITEMS = 500;

function backupFile(code) {
  return path.join(BACKUP_DIR, `${code}.json`);
}

const EMPTY_BACKUP = { favorites: [], pantry: [], budget: null, updatedAt: null, revision: null };

/** Reads a backup file. Never throws: corrupt/absent files read as an empty backup. */
async function readBackupFile(code) {
  try {
    const saved = decryptBackup(await fsp.readFile(backupFile(code), "utf-8"));
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
  await fsp.writeFile(tmp, encryptBackup(payload), { mode: 0o600 });
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
// Google Sign-In account storage
// ---------------------------------------------------------------------------
// A Google ID token is used once to establish identity. The backend then issues
// opaque access/refresh tokens; only SHA-256 token hashes are stored. Account
// profile and state are AES-256-GCM encrypted inside SQLite, with the key kept
// in THRIVE_DATA_ENCRYPTION_KEY on the server. The Google token and plaintext
// profile/state are never written to disk or logs.
//
// THRIVE_GOOGLE_CLIENT_ID is mandatory outside the explicit test override.

const GOOGLE_CLIENT_ID = process.env.THRIVE_GOOGLE_CLIENT_ID || null;

// Test-only: both values are required to short-circuit Google verification.
// Never enable this on a deployed server.
const GOOGLE_TEST_MODE = process.env.THRIVE_GOOGLE_TEST_MODE === "1";
const GOOGLE_TEST_SUB = process.env.THRIVE_GOOGLE_TEST_SUB || null;

const ACCOUNT_DB_PATH = process.env.THRIVE_ACCOUNT_DB || path.join(path.dirname(BACKUP_DIR), "thrive-accounts.sqlite");
let accountStoreInstance = null;
function accountStore() {
  if (!accountStoreInstance) {
    accountStoreInstance = new AccountStore({
      databasePath: ACCOUNT_DB_PATH,
      encryptionKey: process.env.THRIVE_DATA_ENCRYPTION_KEY,
    });
    app.locals.accountStore = accountStoreInstance;
  }
  return accountStoreInstance;
}

/**
 * Verifies a Google ID token with Google's tokeninfo endpoint and returns the
 * verified account { sub, name, email, picture }. Throws with err.status 401
 * on any invalid/expired/tampered token; the caller maps that to a 401.
 */
async function verifyGoogleIdToken(idToken) {
  if (GOOGLE_TEST_MODE && GOOGLE_TEST_SUB) {
    return { sub: GOOGLE_TEST_SUB, name: "Test User", email: "test@example.com", picture: null };
  }
  if (typeof idToken !== "string" || idToken.length < 20 || idToken.length > 4096) {
    const err = new Error("missing or malformed idToken");
    err.status = 401;
    throw err;
  }
  if (!GOOGLE_CLIENT_ID) {
    const err = new Error("Google sign-in is unavailable: THRIVE_GOOGLE_CLIENT_ID is not configured");
    err.status = 503;
    err.expose = true;
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
  if (info.aud !== GOOGLE_CLIENT_ID) {
    const err = new Error("id token audience does not match this server's client id");
    err.status = 401;
    throw err;
  }
  if (info.iss !== "accounts.google.com" && info.iss !== "https://accounts.google.com") {
    const err = new Error("id token issuer is not Google");
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

/** Resolves a short-lived Thrive access token without retaining its plaintext. */
function thriveAccountFrom(req) {
  const header = req.get("authorization") || "";
  const m = header.match(/^Bearer\s+(.+)$/i);
  if (!m) {
    const err = new Error("Authorization: Bearer <Thrive access token> required");
    err.status = 401;
    err.expose = true;
    throw err;
  }
  const account = accountStore().accountForAccessToken(m[1].trim());
  if (!account) {
    const err = new Error("access token is invalid or expired");
    err.status = 401;
    err.expose = true;
    throw err;
  }
  return account;
}

app.post("/api/v1/auth/google", authRateLimit, asyncRoute(async (req0, res) => {
  const idToken = req0.body && typeof req0.body.idToken === "string" ? req0.body.idToken : "";
  if (idToken.length < 20 || idToken.length > 4096) {
    const err = new Error("missing or malformed idToken");
    err.status = 401;
    throw err;
  }
  const store = accountStore();
  const verified = await verifyGoogleIdToken(idToken);
  const session = store.createGoogleSession(verified);
  res.json({
    ok: true,
    sub: verified.sub,
    name: verified.name || "",
    email: verified.email || "",
    picture: verified.picture || "",
    accountKey: googleAccountKey(verified.sub),
    ...session.tokens,
  });
}));

app.post("/api/v1/auth/refresh", authRateLimit, asyncRoute(async (req0, res) => {
  const refreshToken = req0.body && typeof req0.body.refreshToken === "string" ? req0.body.refreshToken : "";
  const session = accountStore().rotateRefreshToken(refreshToken);
  if (!session) {
    const err = new Error("refresh token is invalid or expired");
    err.status = 401;
    err.expose = true;
    throw err;
  }
  res.json({ ok: true, ...session.tokens });
}));

app.post("/api/v1/auth/logout", authRateLimit, asyncRoute(async (req0, res) => {
  const header = req0.get("authorization") || "";
  const m = header.match(/^Bearer\s+(.+)$/i);
  const refreshToken = req0.body && typeof req0.body.refreshToken === "string" ? req0.body.refreshToken : null;
  if (!m && !refreshToken) return res.status(400).json({ error: "an access or refresh token is required" });
  accountStore().revokeSession({ accessToken: m ? m[1].trim() : null, refreshToken });
  res.json({ ok: true });
}));

const APPLIANCE_NAMES = new Map([
  "Stovetop", "Oven", "Microwave", "Air fryer", "Slow cooker",
  "Pressure cooker", "Blender", "Toaster oven", "Grill",
].map((name) => [name.toLowerCase(), name]));
const EMPTY_ACCOUNT_STATE = {
  favorites: [],
  recipeFavorites: [],
  pantry: [],
  budget: null,
  householdProfile: null,
  seenDealIds: [],
  feedRevision: null,
  deletedFavoriteIds: [],
  deletedRecipeFavoriteIds: [],
  deletedPantryItemIds: [],
  deletedShoppingItemIds: [],
  updatedAt: null,
  revision: null,
};

function sanitizeIdList(raw, max = BACKUP_MAX_ITEMS) {
  return [...new Set(raw)]
    .filter((value) => typeof value === "string" && value.length >= 1 && value.length <= 128)
    .slice(0, max);
}

function finiteMoney(value) {
  const parsed = Number(value);
  return Number.isFinite(parsed) ? Math.round(Math.max(0, Math.min(100000, parsed)) * 100) / 100 : 0;
}

function sanitizeHouseholdProfile(raw) {
  if (!raw || typeof raw !== "object" || Array.isArray(raw)) return null;
  const appliances = Array.isArray(raw.appliances)
    ? [...new Set(raw.appliances
        .filter((value) => typeof value === "string")
        .map((value) => APPLIANCE_NAMES.get(value.trim().toLowerCase()))
        .filter(Boolean))]
        .slice(0, APPLIANCE_NAMES.size)
    : [];
  return {
    appliances,
    budgetAmount: finiteMoney(raw.budgetAmount),
    budgetCadence: raw.budgetCadence === "MONTHLY" ? "MONTHLY" : "WEEKLY",
    householdSize: Number.isFinite(Number(raw.householdSize))
      ? Math.max(1, Math.min(20, Math.floor(Number(raw.householdSize))))
      : 1,
    onboardingVersion: Number.isFinite(Number(raw.onboardingVersion))
      ? Math.max(0, Math.min(100, Math.floor(Number(raw.onboardingVersion))))
      : 0,
    onboardingCompletedAt: raw.onboardingCompletedAt == null
      ? null
      : (Number.isFinite(Number(raw.onboardingCompletedAt)) ? Math.max(0, Math.floor(Number(raw.onboardingCompletedAt))) : null),
  };
}

function accountStateBody(saved, updatedAt, revision) {
  const payload = saved && typeof saved === "object" ? saved : {};
  return {
    favorites: Array.isArray(payload.favorites) ? payload.favorites : [],
    recipeFavorites: Array.isArray(payload.recipeFavorites) ? payload.recipeFavorites : [],
    pantry: Array.isArray(payload.pantry) ? payload.pantry : [],
    budget: payload.budget && typeof payload.budget === "object" ? payload.budget : null,
    householdProfile: payload.householdProfile && typeof payload.householdProfile === "object" ? payload.householdProfile : null,
    seenDealIds: Array.isArray(payload.seenDealIds) ? payload.seenDealIds : [],
    feedRevision: typeof payload.feedRevision === "string" ? payload.feedRevision : null,
    deletedFavoriteIds: Array.isArray(payload.deletedFavoriteIds) ? payload.deletedFavoriteIds : [],
    deletedRecipeFavoriteIds: Array.isArray(payload.deletedRecipeFavoriteIds) ? payload.deletedRecipeFavoriteIds : [],
    deletedPantryItemIds: Array.isArray(payload.deletedPantryItemIds) ? payload.deletedPantryItemIds : [],
    deletedShoppingItemIds: Array.isArray(payload.deletedShoppingItemIds) ? payload.deletedShoppingItemIds : [],
    updatedAt: updatedAt || null,
    revision: revision || null,
  };
}

function serveAccountGet(req0, res, account) {
  const current = accountStore().readState(account.accountId);
  const body = current.payload
    ? accountStateBody(current.payload, current.updatedAt, current.revision)
    : EMPTY_ACCOUNT_STATE;
  if (respondWithEtag(req0, res, body)) return;
  res.json(body);
}

async function serveAccountPut(req0, res, account) {
  const body = req0.body && typeof req0.body === "object" && !Array.isArray(req0.body) ? req0.body : {};
  const sectionPresence = {
    favorites: Array.isArray(body.favorites),
    recipeFavorites: Array.isArray(body.recipeFavorites),
    pantry: Array.isArray(body.pantry),
    budget: body.budget !== undefined,
    householdProfile: body.householdProfile !== undefined,
    seenDealIds: Array.isArray(body.seenDealIds),
    feedRevision: body.feedRevision !== undefined,
    deletedFavoriteIds: Array.isArray(body.deletedFavoriteIds),
    deletedRecipeFavoriteIds: Array.isArray(body.deletedRecipeFavoriteIds),
    deletedPantryItemIds: Array.isArray(body.deletedPantryItemIds),
    deletedShoppingItemIds: Array.isArray(body.deletedShoppingItemIds),
  };
  if (!Object.values(sectionPresence).some(Boolean)) {
    return res.status(400).json({
      error: "body must include an account-state section",
    });
  }
  if (body.householdProfile !== undefined && (!body.householdProfile || typeof body.householdProfile !== "object" || Array.isArray(body.householdProfile))) {
    return res.status(400).json({ error: "householdProfile must be an object" });
  }
  if (body.feedRevision !== undefined && body.feedRevision !== null && (typeof body.feedRevision !== "string" || body.feedRevision.length > 128)) {
    return res.status(400).json({ error: "feedRevision must be null or a short string" });
  }
  for (const field of ["deletedFavoriteIds", "deletedRecipeFavoriteIds", "deletedPantryItemIds", "deletedShoppingItemIds"]) {
    if (body[field] !== undefined && !Array.isArray(body[field])) {
      return res.status(400).json({ error: `${field} must be an array` });
    }
  }
  const ifMatch = req0.get("if-match");
  if (ifMatch === undefined) return res.status(428).json({ error: "If-Match header required (use \"*\" to create)" });

  const stored = await serializeBackup(`account:${account.accountId}`, async () => {
    const current = accountStore().readState(account.accountId);
    if (ifMatch === "*") {
      if (current.revision !== null) {
        const error = new Error("backup already exists — re-pull with the current revision");
        error.status = 409;
        error.expose = true;
        error.currentRevision = current.revision;
        throw error;
      }
    } else if (current.revision === null) {
      const error = new Error("no backup exists for this account — use If-Match: * to create");
      error.status = 404;
      error.expose = true;
      throw error;
    } else if (current.revision !== ifMatch) {
      const error = new Error("conflict — the backup changed since you read it");
      error.status = 409;
      error.expose = true;
      error.currentRevision = current.revision;
      throw error;
    }

    const saved = current.payload || EMPTY_ACCOUNT_STATE;
    const next = {
      favorites: sectionPresence.favorites ? sanitizeFavorites(body.favorites) : saved.favorites || [],
      recipeFavorites: sectionPresence.recipeFavorites ? sanitizeIdList(body.recipeFavorites) : saved.recipeFavorites || [],
      pantry: sectionPresence.pantry ? sanitizePantry(body.pantry) : saved.pantry || [],
      budget: sectionPresence.budget ? sanitizeBudget(body.budget) : saved.budget || null,
      householdProfile: sectionPresence.householdProfile ? sanitizeHouseholdProfile(body.householdProfile) : saved.householdProfile || null,
      seenDealIds: sectionPresence.seenDealIds ? sanitizeIdList(body.seenDealIds, 10000) : saved.seenDealIds || [],
      feedRevision: sectionPresence.feedRevision ? (body.feedRevision === null ? null : body.feedRevision) : saved.feedRevision || null,
      deletedFavoriteIds: sectionPresence.deletedFavoriteIds ? sanitizeIdList(body.deletedFavoriteIds, 10000) : saved.deletedFavoriteIds || [],
      deletedRecipeFavoriteIds: sectionPresence.deletedRecipeFavoriteIds ? sanitizeIdList(body.deletedRecipeFavoriteIds, 10000) : saved.deletedRecipeFavoriteIds || [],
      deletedPantryItemIds: sectionPresence.deletedPantryItemIds ? sanitizeIdList(body.deletedPantryItemIds, 10000) : saved.deletedPantryItemIds || [],
      deletedShoppingItemIds: sectionPresence.deletedShoppingItemIds ? sanitizeIdList(body.deletedShoppingItemIds, 10000) : saved.deletedShoppingItemIds || [],
    };
    const revision = crypto.randomBytes(16).toString("hex");
    const updatedAt = accountStore().writeState(account.accountId, next, revision);
    return { payload: next, revision, updatedAt };
  });

  res.json({
    ok: true,
    favorites: stored.payload.favorites.length,
    recipeFavorites: stored.payload.recipeFavorites.length,
    pantry: stored.payload.pantry.length,
    budget: stored.payload.budget ? stored.payload.budget.items.length : 0,
    appliances: stored.payload.householdProfile ? stored.payload.householdProfile.appliances.length : 0,
    seenDealIds: stored.payload.seenDealIds.length,
    tombstones: stored.payload.deletedFavoriteIds.length + stored.payload.deletedRecipeFavoriteIds.length +
      stored.payload.deletedPantryItemIds.length + stored.payload.deletedShoppingItemIds.length,
    updatedAt: stored.updatedAt,
    revision: stored.revision,
  });
}

app.get("/api/v1/account/backup", accountRateLimit, asyncRoute(async (req0, res) => {
  const account = thriveAccountFrom(req0);
  return serveAccountGet(req0, res, account);
}));

app.put("/api/v1/account/backup", accountRateLimit, asyncRoute(async (req0, res) => {
  const account = thriveAccountFrom(req0);
  return serveAccountPut(req0, res, account);
}));

app.delete("/api/v1/account", accountRateLimit, asyncRoute(async (req0, res) => {
  const account = thriveAccountFrom(req0);
  accountStore().deleteAccount(account.accountId);
  res.json({ ok: true, deleted: true });
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
  app.listen(PORT, HOST, () => {
    console.log(`Thrive sync API listening on http://${HOST}:${PORT}`);
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
