"use strict";

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

module.exports = { DailyRotationSource, PartnerApiSource };
