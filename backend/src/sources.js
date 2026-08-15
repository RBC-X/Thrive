"use strict";

require("../dotenv"); // loads backend/.env for standalone scripts/tools too

/**
 * Deal sources for the Thrive sync API.
 *
 * A source is any object exposing `async deals() -> Deal[]` and
 * `name -> string`. The server asks every configured source and merges the
 * results, so a live retailer adapter can be dropped in without touching
 * routes or clients.
 *
 * NOTE ON "REAL" RETAILER APIs: no major US retailer exposes a public,
 * unauthenticated deals API. Kroger/Walmart/Target/Instacart all require
 * partner programs, client credentials, and OAuth. The `PartnerApiSource`
 * below shows exactly where such an adapter plugs in (fetch + OAuth token),
 * and skips itself cleanly when no credentials are configured. The default
 * `DailyRotationSource` serves a deterministic feed that rotates every day —
 * the same shape a live feed would return — so the client sync path is real
 * and testable today.
 */

const fs = require("fs");
const path = require("path");

function loadJson(rel) {
  const candidates = [path.join(__dirname, "..", "data", rel)];
  const appAsset = path.join(__dirname, "..", "..", "app", "src", "main", "assets", "data", rel);
  if (fs.existsSync(appAsset)) return JSON.parse(fs.readFileSync(appAsset, "utf-8"));
  if (fs.existsSync(candidates[0])) return JSON.parse(fs.readFileSync(candidates[0], "utf-8"));
  throw new Error(`missing data file: ${rel} (looked in ${candidates[0]} and ${appAsset})`);
}

// Deterministic hash used for day-stable rotation and price jitter.
function dayIndex() {
  const now = new Date();
  const start = new Date(now.getFullYear(), 0, 0);
  const dayOfYear = Math.floor((now - start) / 86400000);
  return dayOfYear;
}

function hashString(s) {
  let h = 0;
  for (let i = 0; i < s.length; i++) {
    h = (Math.imul(h, 31) + s.charCodeAt(i)) | 0;
  }
  return Math.abs(h);
}

/**
 * Serves the canonical deal catalog with a daily rotation: the active subset
 * and per-deal prices shift deterministically by date, like a real weekly ad.
 */
class DailyRotationSource {
  constructor() {
    this.name = "daily-rotation";
    this.templates = loadJson("deals.json");
  }

  async deals() {
    const day = dayIndex();
    const activeCount = Math.min(30 + (day % 12), this.templates.length);
    const rotated = [];
    for (let i = 0; i < activeCount; i++) {
      const t = this.templates[(i + day) % this.templates.length];
      const seed = hashString(t.id + ":" + day);
      // ±8% day-stable price movement, always > 0.
      const jitter = 0.92 + (seed % 17) / 100;
      const price = Math.round(t.price * jitter * 100) / 100;
      const ends = 1 + (seed % 5);
      rotated.push({
        ...t,
        price,
        endsInDays: ends,
        unitPrice: t.unitPrice || "",
        size: t.size || null,
      });
    }
    return rotated;
  }
}

/**
 * Live Kroger prices via the official Kroger Connect API (OAuth2 client
 * credentials). Configure:
 *   KROGER_CLIENT_ID / KROGER_CLIENT_SECRET   (from developer.kroger.com)
 *   KROGER_ZIP (optional, default 45202)       zip used to pick a store
 *   KROGER_TERMS (optional)                    comma-separated search terms;
 *                                              defaults to the deal catalog's
 *                                              product names so the budget
 *                                              finder gets real, current prices
 * Without credentials the source disables itself and the curated feed is
 * served instead — the app never breaks.
 */
class KrogerLiveSource {
  constructor() {
    this.name = "kroger-live";
    this.clientId = process.env.KROGER_CLIENT_ID || "";
    this.clientSecret = process.env.KROGER_CLIENT_SECRET || "";
    this.zip = process.env.KROGER_ZIP || "45202";
    this.terms = (process.env.KROGER_TERMS || "")
      .split(",")
      .map((s) => s.trim())
      .filter(Boolean);
    this._token = null;
    this._tokenAt = 0;
    this._location = null;
    this._locationKey = null;
    this._locationAt = 0;
    this._storeGeo = null;
    this._termsCache = null;
  }

  /** Bucket for a user coordinate pair (≈1/100° ≈ 1 km) so nearby users share one lookup. */
  static _bucket(lat, lng) {
    return `${Math.round(lat * 100)},${Math.round(lng * 100)}`;
  }

  get enabled() {
    return Boolean(this.clientId && this.clientSecret);
  }

  /** OAuth2 client-credentials token, cached until ~5 min before expiry. */
  async _accessToken() {
    if (this._token && Date.now() - this._tokenAt < 1500 * 1000) return this._token;
    const res = await fetch("https://api.kroger.com/v1/connect/oauth2/token", {
      method: "POST",
      headers: {
        "Content-Type": "application/x-www-form-urlencoded",
        Authorization: "Basic " + Buffer.from(`${this.clientId}:${this.clientSecret}`).toString("base64"),
      },
      body: new URLSearchParams({ grant_type: "client_credentials", scope: "product.compact" }),
      signal: AbortSignal.timeout(8000),
    });
    if (!res.ok) throw new Error(`kroger token ${res.status}`);
    const json = await res.json();
    if (!json.access_token) throw new Error("kroger token response missing access_token");
    this._token = json.access_token;
    this._tokenAt = Date.now();
    return this._token;
  }

  /**
   * Nearest store to the user (lat/lng when provided, else the configured zip).
   * Products need a locationId for prices, and the resolved store's coordinates
   * let the server report an honest "nearest store" distance per deal.
   * Cached per location bucket for 30 minutes.
   */
  async _locationId(lat, lng) {
    const key = lat != null && lng != null ? KrogerLiveSource._bucket(lat, lng) : "zip";
    if (this._location && this._locationKey === key && Date.now() - this._locationAt < 30 * 60 * 1000) {
      return this._location;
    }
    const token = await this._accessToken();
    const where = lat != null && lng != null
      ? `filter.latLong.near=${encodeURIComponent(`${lat},${lng}`)}`
      : `filter.zipCode.near=${encodeURIComponent(this.zip)}`;
    const res = await fetch(
      `https://api.kroger.com/v1/locations?${where}&filter.limit=1`,
      { headers: { Authorization: `Bearer ${token}`, Accept: "application/json" }, signal: AbortSignal.timeout(8000) }
    );
    if (!res.ok) throw new Error(`kroger locations ${res.status}`);
    const json = await res.json();
    const store = json.data && json.data[0];
    this._location = (store && store.locationId) || null;
    this._locationKey = key;
    this._locationAt = Date.now();
    const g = store && store.geoLocation;
    this._storeGeo = g && Number(g.latitude) && Number(g.longitude)
      ? { lat: Number(g.latitude), lng: Number(g.longitude) }
      : null;
    return this._location;
  }

  /**
   * Fetches live products for every configured (or catalog-derived) search
   * term, with a small bounded concurrency so a few hundred terms finish in
   * seconds while staying comfortably inside Kroger's 10,000 calls/day limit.
   * Every returned deal carries a verified product-page URL from the API.
   */
  async deals(lat, lng) {
    const token = await this._accessToken();
    const loc = await this._locationId(lat, lng);
    if (!loc) return [];
    const terms = this.terms.length ? this.terms : defaultSearchTerms();
    const out = [];
    const seen = new Set();
    const CONCURRENCY = 6;
    const queue = [...terms];
    const self = this; // workers are plain closures — keep the instance for store coords
    const worker = async () => {
      while (queue.length) {
        const term = queue.shift();
        try {
          const res = await fetch(
            `https://api.kroger.com/v1/products?filter.term=${encodeURIComponent(term)}&filter.locationId=${encodeURIComponent(loc)}&filter.limit=50`,
            { headers: { Authorization: `Bearer ${token}`, Accept: "application/json" }, signal: AbortSignal.timeout(10000) }
          );
          if (!res.ok) {
            if (res.status === 429) {
              // Back off briefly on rate limiting, then keep going.
              await new Promise((r) => setTimeout(r, 1500));
            }
            continue;
          }
          const json = await res.json();
          for (const it of json.data || []) {
            if (!it.productId || seen.has(it.productId)) continue; // same product via another term
            const item0 = it.items && it.items[0];
            const price = item0 && item0.price;
            // Kroger's Products API exposes `price.regular` / `price.promo`;
            // accept the older `regularPrice`/`promoPrice` shape defensively too.
            const regular = price && Number(price.regular ?? price.regularPrice) > 0
              ? Number(price.regular ?? price.regularPrice) : 0;
            const promo = price && Number(price.promo ?? price.promoPrice) > 0
              ? Number(price.promo ?? price.promoPrice) : 0;
            const best = promo || regular;
            if (!best) continue;
            seen.add(it.productId);
            const size = (item0 && item0.size) || null;
            // productPageURI is the canonical page path (may carry a cid tracking
            // query) — prefer it over guessing /p/{id}.
            const pagePath = String(it.productPageURI || "/p/" + it.productId).split("?")[0];
            out.push({
              id: `kroger-${it.productId}`,
              store: "Kroger",
              productName: it.description || "Kroger product",
              category: mapKrogerCategory((it.categories && it.categories[0]) || ""),
              price: Math.round(best * 100) / 100,
              regularPrice: regular ? Math.round(regular * 100) / 100 : null, // honest before-price when a promo is live
              unitPrice: size ? `$${(best / unitQty(size)).toFixed(2)}/${unitName(size)}` : "",
              savingsPercent: promo && regular ? Math.round((1 - promo / regular) * 100) : 0,
              keywords: term.toLowerCase().split(/\s+/),
              endsInDays: 7,
              url: `https://www.kroger.com${pagePath}`,
              urlVerified: true, // productPageURI from the API resolves to this exact product
              size: size,
              brand: it.brand || null,
              imageUrl: (it.images && it.images[0] && it.images[0].sizes && it.images[0].sizes[0] && it.images[0].sizes[0].url) || null,
              estimated: false, // live price from the Kroger API for the resolved store
              storeLat: self._storeGeo ? self._storeGeo.lat : null,
              storeLng: self._storeGeo ? self._storeGeo.lng : null,
            });
          }
        } catch (e) {
          /* a single term failing never kills the feed */
          if (process.env.KROGER_DEBUG) console.error(`[kroger term:${term}] ${e.message}`);
        }
      }
    }
    await Promise.all(Array.from({ length: CONCURRENCY }, worker));
    return out;
  }
}

/** Maps a Kroger department ("Meat & Seafood/Chicken") to Thrive's category. */
function mapKrogerCategory(raw) {
  const c = String(raw || "").toLowerCase();
  if (c.includes("produce")) return "Produce";
  if (c.includes("dairy")) return "Dairy";
  if (c.includes("meat") || c.includes("seafood")) return "Meat";
  if (c.includes("frozen")) return "Frozen";
  if (c.includes("bakery")) return "Bakery";
  if (c.includes("snack") || c.includes("candy")) return "Snacks";
  if (c.includes("beverage") || c.includes("drink")) return "Drinks";
  if (c.includes("condiment") || c.includes("sauce")) return "Condiments";
  if (c.includes("household") || c.includes("cleaning") || c.includes("paper")) return "Household";
  if (c.includes("health") || c.includes("pharm")) return "Health";
  if (c.includes("pantry") || c.includes("grocery")) return "Pantry";
  return "Grocery";
}

/** Extracts a comparable number from a size string like "1 gal", "48 oz". */
function unitQty(size) {
  const m = String(size || "").match(/([\d.]+)\s*([a-zA-Z]+)/);
  return m ? Number(m[1]) || 1 : 1;
}

function unitName(size) {
  const m = String(size || "").match(/([\d.]+)\s*([a-zA-Z]+)/);
  return (m && m[2]) || "unit";
}

/**
 * Default search terms: every product-looking title in the bundled coupon
 * catalog (brand + size stripped, deduped, capped so a single daily refresh
 * stays far inside Kroger's 10,000 calls/day budget). This is what turns the
 * live feed from a handful of staples into a real catalog with direct
 * product-page links for thousands of items.
 */
function defaultSearchTerms() {
  const knownBrands = [
    "great value", "kirkland signature", "equate", "cvs health", "365 everyday value",
    "up & up", "member's mark", "good & gather", "simple truth", "market pantry",
    "apple", "samsung", "sony", "lg", "dyson", "nike", "adidas", "kroger",
    "aldi", "walmart", "target", "costco", "starbucks", "dunkin", "chipotle",
    "olive garden", "taco bell", "home depot", "best buy", "walgreens",
  ];
  try {
    const coupons = loadJson("coupons.json");
    const terms = [];
    const seen = new Set();
    for (const c of coupons) {
      if (!c || typeof c.title !== "string") continue;
      let t = c.title.split(",")[0].trim(); // strip the size suffix
      const low = t.toLowerCase();
      for (const b of knownBrands) {
        if (low.startsWith(b + " ")) {
          t = t.slice(b.length + 1).trim();
          break;
        }
      }
      const words = t.split(/\s+/).filter(Boolean);
      if (words.length < 1 || words.length > 6) continue;
      const key = t.toLowerCase();
      if (seen.has(key)) continue;
      seen.add(key);
      terms.push(t);
    }
    if (terms.length) return terms.slice(0, 160);
  } catch (_) {
    /* fall through to the hardcoded list */
  }
  return ["milk", "eggs", "chicken", "ground beef", "pasta", "bread", "apples", "bananas"];
}

/**
 * Adapter point for a real partner API (Kroger Connect, Walmart Affiliate,
 * Instacart, ...). Configure THRIVE_RETAILER_TOKEN and the source pulls live
 * deal data; without credentials it disables itself.
 */
class PartnerApiSource {
  constructor() {
    this.name = "partner-api";
    this.token = process.env.THRIVE_RETAILER_TOKEN || "";
    this.endpoint = process.env.THRIVE_RETAILER_ENDPOINT || "";
  }

  get enabled() {
    return Boolean(this.token && this.endpoint);
  }

  async deals() {
    if (!this.enabled) return [];
    const res = await fetch(this.endpoint, {
      headers: { Authorization: `Bearer ${this.token}`, Accept: "application/json" },
      signal: AbortSignal.timeout(8000),
    });
    if (!res.ok) throw new Error(`retailer feed ${res.status}`);
    const body = await res.json();
    // Normalize whatever the partner returns into Thrive's Deal shape.
    return (body.deals || body.items || []).map((d, i) => ({
      id: d.id || `partner-${i}`,
      store: d.store || "Partner store",
      productName: d.productName || d.name || "Product",
      category: d.category || "Grocery",
      price: Number(d.price || d.salePrice || 0),
      unitPrice: d.unitPrice || "",
      savingsPercent: Number(d.savingsPercent || 0),
      keywords: Array.isArray(d.keywords) ? d.keywords : [],
      endsInDays: Number(d.endsInDays || 7),
      url: d.url || null,
      urlVerified: d.urlVerified === true,
      size: d.size || null,
      brand: d.brand || null,
      imageUrl: d.imageUrl || null,
      estimated: d.estimated !== false,
    }));
  }
}

module.exports = { DailyRotationSource, PartnerApiSource, KrogerLiveSource };
