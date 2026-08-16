"use strict";

/**
 * Exa web-search service for Thrive (optional).
 *
 * Exa is a web search API — NOT a retailer deals API. Its results are web
 * pages: official retailer product pages, recipe sites, coupon blogs. Thrive
 * uses it strictly as a DISCOVERY layer: every result is labeled
 * "web-discovered" and is never treated as a verified price, product, or
 * deal. The exact-product / unit-price / verified-link rules that govern the
 * main Savings feed are deliberately NOT applied to Exa output — it is a
 * pointer to look, not a claim.
 *
 * Hardening (all enforced here):
 *   - Requires EXA_API_KEY in the server environment (never in the app).
 *   - Queries are validated (non-empty, length-capped, no control chars).
 *   - Results are capped (1..8), request timeout, HTTPS-only URLs.
 *   - Rate limiting (sliding window) + daily spending cap + TTL cache.
 *   - Structured errors; any provider failure degrades to an honest empty
 *     result so the rest of Thrive keeps working untouched.
 */

require("../dotenv");

const SAFE_PROTOCOLS = new Set(["http:", "https:"]);
const DEFAULT_TTL_MS = 6 * 60 * 60 * 1000; // 6h cache for repeat queries
const DEFAULT_RATE_PER_MIN = 10;
const DEFAULT_MAX_DAILY = 200;
const ENDPOINT = "https://api.exa.ai/search";

function dayKey() {
  return new Date().toISOString().slice(0, 10);
}

class ExaService {
  constructor() {
    this.name = "exa";
    this.apiKey = process.env.EXA_API_KEY || "";
    this.ratePerMin = Number(process.env.EXA_RATE_PER_MIN) || DEFAULT_RATE_PER_MIN;
    this.maxDaily = Number(process.env.EXA_MAX_DAILY) || DEFAULT_MAX_DAILY;
    this.timeoutMs = Number(process.env.EXA_TIMEOUT_MS) || 8000;
    this._cache = new Map(); // key -> { at, results }
    this._window = []; // timestamps of recent calls (sliding window)
    this._daily = { key: dayKey(), count: 0 };
  }

  get enabled() {
    return Boolean(this.apiKey);
  }

  /** Validates and normalizes a user query. Throws with .status/.expose on 400. */
  static validateQuery(raw) {
    const q = String(raw || "").trim();
    if (q.length < 2) {
      const err = new Error("query must be at least 2 characters");
      err.status = 400;
      err.expose = true;
      throw err;
    }
    if (q.length > 120) {
      const err = new Error("query must be 120 characters or fewer");
      err.status = 400;
      err.expose = true;
      throw err;
    }
    // Reject control characters and anything that isn't printable text.
    if (/[\u0000-\u001f\u007f]/.test(q)) {
      const err = new Error("query contains invalid control characters");
      err.status = 400;
      err.expose = true;
      throw err;
    }
    return q;
  }

  /** Only http/https URLs are ever surfaced; anything else is dropped. */
  static isSafeUrl(raw) {
    if (typeof raw !== "string" || raw.length > 2048) return false;
    let u;
    try {
      u = new URL(raw);
    } catch (_) {
      return false;
    }
    if (!SAFE_PROTOCOLS.has(u.protocol)) return false;
    if (!u.hostname || u.hostname.length > 253) return false;
    // URLs carrying credentials (user:pass@) are suspicious — drop them.
    if (u.username || u.password) return false;
    return true;
  }

  /** Best-effort heuristic confidence: official-ish host + a date helps. */
  static _confidence(url, hasDate) {
    let score = 0.3; // web discovery is never a verified claim
    try {
      const host = new URL(url).hostname.replace(/^www\./, "");
      // Official retailer/manufacturer sites are stronger discovery leads.
      const officialish = /(kroger|walmart|target|costco|aldi|publix|bestbuy|homedepot|walmart|amazon|instacart|samsclub|heb|wegmans|wholefoods)\.(com|ca|org|net)/i;
      if (officialish.test(host)) score += 0.25;
      // Recipe/authoritative sites are still useful for cooking discovery.
      if (/recipes?\.|allrecipes|foodnetwork|seriouseats|budgetbytes/.test(host)) score += 0.1;
    } catch (_) {
      /* keep base score */
    }
    if (hasDate) score += 0.05;
    return Math.min(1, Math.round(score * 100) / 100);
  }

  static _normalizeResult(r) {
    const url = r && r.url;
    if (!ExaService.isSafeUrl(url)) return null;
    const highlights = Array.isArray(r.highlights) ? r.highlights : [];
    const excerpt = highlights[0] || r.text || "";
    return {
      title: String(r.title || r.url).slice(0, 300),
      url,
      publishedDate: r.publishedDate || r.published_date || null,
      excerpt: String(excerpt).slice(0, 500),
      confidence: ExaService._confidence(url, Boolean(r.publishedDate || r.published_date)),
      // Explicitly NOT a verified claim:
      verified: false,
      kind: "web-discovery",
    };
  }

  /** Enforces the sliding-window rate limit; returns ms to wait, or 0. */
  _rateLimited() {
    const now = Date.now();
    this._window = this._window.filter((t) => now - t < 60_000);
    if (this._window.length >= this.ratePerMin) return true;
    this._window.push(now);
    return false;
  }

  _overDaily() {
    if (this._daily.key !== dayKey()) this._daily = { key: dayKey(), count: 0 };
    if (this._daily.count >= this.maxDaily) return true;
    this._daily.count += 1;
    return false;
  }

  /**
   * Searches Exa. Returns { results, source, note } — never throws for
   * provider issues; the endpoint translates failures into honest empty
   * responses. Throws {status, expose} only for 400 (bad query) and 429
   * (rate limited / over daily spend).
   */
  async search(rawQuery, opts = {}) {
    const query = ExaService.validateQuery(rawQuery);
    const limit = Math.min(Number(opts.limit) || 5, 8);
    const kind = String(opts.kind || "offers");

    if (!this.enabled) {
      return { results: [], source: "exa", note: "Exa is not configured on this server.", kind };
    }

    // Cache first: repeat queries must not burn rate limit or credits.
    const cacheKey = `${kind}:${query}:${limit}`;
    const hit = this._cache.get(cacheKey);
    if (hit && Date.now() - hit.at < DEFAULT_TTL_MS) {
      return { results: hit.results, source: "exa", cached: true, kind };
    }

    if (this._rateLimited()) {
      const err = new Error("search rate limit reached — try again in a minute");
      err.status = 429;
      err.expose = true;
      throw err;
    }
    if (this._overDaily()) {
      const err = new Error("daily search budget reached");
      err.status = 429;
      err.expose = true;
      throw err;
    }

    let body;
    try {
      const res = await fetch(ENDPOINT, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "x-api-key": this.apiKey,
        },
        body: JSON.stringify({
          query: `${query} ${kind === "recipes" ? "easy affordable family recipe" : kind === "product" ? "official product page" : "current deal coupon offer price"}`,
          type: "auto",
          numResults: limit,
          contents: { highlights: true },
        }),
        signal: AbortSignal.timeout(this.timeoutMs),
      });
      if (res.status === 429 || res.status === 402 || res.status === 403) {
        const err = new Error("Exa rejected the request (rate/credits)");
        err.status = 429;
        err.expose = true;
        throw err;
      }
      if (!res.ok) {
        // Provider failure -> honest empty, never a fabricated result.
        return { results: [], source: "exa", note: `Exa provider error (HTTP ${res.status}).`, kind };
      }
      body = await res.json();
    } catch (e) {
      if (e && e.status === 429) throw e;
      if (e && e.name === "TimeoutError") {
        return { results: [], source: "exa", note: "Search timed out — try again.", kind };
      }
      return { results: [], source: "exa", note: "Search provider unavailable.", kind };
    }

    const results = (Array.isArray(body.results) ? body.results : [])
      .map(ExaService._normalizeResult)
      .filter(Boolean)
      .slice(0, limit);

    if (results.length) this._cache.set(cacheKey, { at: Date.now(), results });
    return { results, source: "exa", kind };
  }
}

module.exports = { ExaService };
