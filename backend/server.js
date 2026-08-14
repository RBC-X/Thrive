"use strict";

const express = require("express");
const cors = require("cors");
const crypto = require("crypto");
const fs = require("fs");
const path = require("path");
const { DailyRotationSource, PartnerApiSource } = require("./src/sources");

const app = express();
app.use(cors());
app.use(express.json());

const PORT = Number(process.env.PORT || 4000) || 4000;

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

const sources = [new DailyRotationSource(), new PartnerApiSource()].filter((s) => s.enabled !== false);

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
    coupons,
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

app.get("/api/v1/deals", async (req0, res) => {
  const deals = await getDeals();
  let out = deals;
  if (req0.query.category) {
    out = out.filter((d) => d.category.toLowerCase() === String(req0.query.category).toLowerCase());
  }
  if (req0.query.limit) out = out.slice(0, Number(req0.query.limit));
  if (respondWithEtag(req0, res, out)) return;
  res.json({ deals: out, generatedAt: new Date().toISOString() });
});

app.get("/api/v1/coupons", (req0, res) => {
  let out = coupons;
  if (req0.query.category) {
    out = out.filter((c) => c.category.toLowerCase() === String(req0.query.category).toLowerCase());
  }
  if (req0.query.limit) out = out.slice(0, Number(req0.query.limit));
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

app.get("/api/v1/sync", async (req0, res) => {
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
});

// Manual override: POST a deals array to preview a custom feed. The server may
// be reachable over a public tunnel, so this write route is guarded by a shared
// admin token (THRIVE_ADMIN_TOKEN). Without a token configured, the route is
// disabled entirely — the app never POSTs, so nothing legitimately breaks.
const ADMIN_TOKEN = process.env.THRIVE_ADMIN_TOKEN || null;
app.post("/api/v1/deals", (req0, res) => {
  if (!ADMIN_TOKEN) {
    return res.status(403).json({ error: "admin override disabled (set THRIVE_ADMIN_TOKEN to enable)" });
  }
  const provided = req0.get("x-thrive-admin-token");
  if (!provided || provided !== ADMIN_TOKEN) {
    return res.status(401).json({ error: "unauthorized" });
  }
  if (!Array.isArray(req0.body)) {
    return res.status(400).json({ error: "body must be an array of deals" });
  }
  dealsCache = req0.body;
  dealsCacheAt = new Date().toISOString().slice(0, 10);
  res.json({ ok: true, deals: dealsCache.length });
});

app.listen(PORT, () => {
  console.log(`Thrive sync API listening on http://localhost:${PORT}`);
  console.log(`Sources: ${sources.map((s) => s.name).join(", ")}`);
  console.log(`  GET /api/v1/health`);
  console.log(`  GET /api/v1/sync   (ETag-cached full payload)`);
  console.log(`  GET /api/v1/deals | /coupons | /recipes | /catalog`);
});
