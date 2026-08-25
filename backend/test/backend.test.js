"use strict";

const { test, before, after } = require("node:test");
const assert = require("node:assert/strict");
const fs = require("fs");
const os = require("os");
const path = require("path");

// Isolated data dir + random port; the server app is required (not spawned),
// so process guards are inherited and health is checkable after every failure.
const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), "thrive-test-"));
process.env.THRIVE_BACKUP_DIR = path.join(tmpDir, "backups");
process.env.THRIVE_ADMIN_TOKEN = "test-admin-token";
process.env.THRIVE_TEST_BUNDLED_ONLY = "1";
// Keep this route-suite deterministic even when a developer's .env contains
// retailer credentials; the dedicated Kroger/Target suites cover live sources.
delete process.env.KROGER_CLIENT_ID;
delete process.env.KROGER_CLIENT_SECRET;
delete process.env.TARGET_API_KEY;

const app = require("../server");
let server;
let base;

before(async () => {
  await new Promise((resolve) => {
    server = app.listen(0, () => {
      base = `http://127.0.0.1:${server.address().port}`;
      resolve();
    });
  });
});

after(async () => {
  await new Promise((resolve) => server.close(resolve));
  fs.rmSync(tmpDir, { recursive: true, force: true });
});

async function req(method, pathname, { body, headers = {} } = {}) {
  const opts = { method, headers };
  if (body !== undefined) {
    opts.headers["Content-Type"] = "application/json";
    opts.body = typeof body === "string" ? body : JSON.stringify(body);
  }
  const res = await fetch(base + pathname, opts);
  let json = null;
  try {
    json = await res.json();
  } catch {
    /* non-JSON body */
  }
  return { status: res.status, headers: res.headers, json };
}

const BACKUP = "testcode1";
const pantry = (id, name) => ({
  id,
  name,
  category: "Grocery",
  location: "Pantry",
  quantity: 1,
  unit: "",
  expiresAt: null,
  addedAt: 0,
});
const budget = (items) => ({ budget: 100, people: 2, items });

// ---------------------------------------------------------------------------
// Health + sync + ETag
// ---------------------------------------------------------------------------

test("health is up and lists sources", async () => {
  const r = await req("GET", "/api/v1/health");
  assert.equal(r.status, 200);
  assert.equal(r.json.ok, true);
  assert.ok(Array.isArray(r.json.sources));
});

test("sync returns the full payload with an ETag and 304s on match", async () => {
  const r1 = await req("GET", "/api/v1/sync");
  assert.equal(r1.status, 200);
  // Without retailer credentials the server falls back to useful planning
  // estimates. Search links are intentionally never called verified product
  // links, and snapshot prices are intentionally never called live.
  assert.ok(r1.json.coupons.length > 10, "bundled planning estimates present");
  for (const c of r1.json.coupons) {
    assert.equal(c.urlVerified, false, `coupon ${c.id} search link must not be called verified`);
    assert.equal(c.estimated, true, `coupon ${c.id} bundled price must stay estimated`);
    assert.ok(c.url && c.url.startsWith("https://"), `coupon ${c.id} must keep an https url`);
  }
  assert.ok(Array.isArray(r1.json.deals));
  assert.ok(Array.isArray(r1.json.recipes));
  assert.ok(Array.isArray(r1.json.catalog));
  const etag = r1.headers.get("etag");
  assert.ok(etag, "etag present");
  const r2 = await req("GET", "/api/v1/sync", { headers: { "If-None-Match": etag } });
  assert.equal(r2.status, 304);
});

// ---------------------------------------------------------------------------
// Strict query validation
// ---------------------------------------------------------------------------

test("deals rejects non-integer/out-of-range limits with 400", async () => {
  assert.equal((await req("GET", "/api/v1/deals?limit=abc")).status, 400);
  assert.equal((await req("GET", "/api/v1/deals?limit=0")).status, 400);
  assert.equal((await req("GET", "/api/v1/deals?limit=-3")).status, 400);
  assert.equal((await req("GET", "/api/v1/deals?limit=99999")).status, 400);
  const ok = await req("GET", "/api/v1/deals?limit=3");
  assert.equal(ok.status, 200);
  assert.equal(ok.json.deals.length, 3);
});

test("deals rejects control-char categories with 400", async () => {
  const r = await req("GET", "/api/v1/deals?category=%0aGrocery");
  assert.equal(r.status, 400);
});

// ---------------------------------------------------------------------------
// Backup: revision + If-Match + section merge + concurrency
// ---------------------------------------------------------------------------

test("backup PUT requires If-Match", async () => {
  const r = await req("PUT", `/api/v1/backup/${BACKUP}`, { body: { favorites: ["c1"] } });
  assert.equal(r.status, 428);
});

test("backup create/read/update with revisions", async () => {
  const create = await req("PUT", `/api/v1/backup/${BACKUP}`, {
    body: { favorites: ["c1"] },
    headers: { "If-Match": "*" },
  });
  assert.equal(create.status, 200);
  const rev1 = create.json.revision;
  assert.ok(rev1);

  const read = await req("GET", `/api/v1/backup/${BACKUP}`);
  assert.equal(read.status, 200);
  assert.equal(read.json.revision, rev1);
  assert.deepEqual(read.json.favorites, ["c1"]);

  // Stale revision -> 409 with the current revision in the body.
  const stale = await req("PUT", `/api/v1/backup/${BACKUP}`, {
    body: { favorites: ["c2"] },
    headers: { "If-Match": "bogus-revision" },
  });
  assert.equal(stale.status, 409);
  assert.equal(stale.json.currentRevision, rev1);

  // Correct revision -> 200.
  const update = await req("PUT", `/api/v1/backup/${BACKUP}`, {
    body: { favorites: ["c1", "c2"] },
    headers: { "If-Match": rev1 },
  });
  assert.equal(update.status, 200);
  assert.notEqual(update.json.revision, rev1);

  // Replaying the same revision -> 409 (no silent double-write).
  const replay = await req("PUT", `/api/v1/backup/${BACKUP}`, {
    body: { favorites: ["c1", "c2"] },
    headers: { "If-Match": rev1 },
  });
  assert.equal(replay.status, 409);
});

test("backup section merge: pantry-only push preserves budget and vice versa", async () => {
  const code = "merge1ab";
  const a = await req("PUT", `/api/v1/backup/${code}`, {
    body: { pantry: [pantry("p1", "Oats")] },
    headers: { "If-Match": "*" },
  });
  assert.equal(a.status, 200);
  const rev = a.json.revision;

  const b = await req("PUT", `/api/v1/backup/${code}`, {
    body: { budget: budget([{ id: "s1", name: "Milk", category: "Grocery", quantity: 1, unit: "gal", estPrice: 3.5, checked: false, brand: null }]) },
    headers: { "If-Match": rev },
  });
  assert.equal(b.status, 200);

  const read = await req("GET", `/api/v1/backup/${code}`);
  assert.equal(read.json.pantry.length, 1, "pantry survived the budget-only push");
  assert.equal(read.json.budget.items.length, 1);
});

test("two concurrent writers preserve the union (no lost updates)", async () => {
  const code = "concurrent";
  const rev = (await req("PUT", `/api/v1/backup/${code}`, { body: { favorites: [] }, headers: { "If-Match": "*" } })).json.revision;

  // Both devices read the same revision, then each adds its own favorite.
  const devA = (await req("GET", `/api/v1/backup/${code}`)).json.revision;
  const devB = (await req("GET", `/api/v1/backup/${code}`)).json.revision;
  assert.equal(devA, devB, "both devices read the same revision");

  const aWrite = await req("PUT", `/api/v1/backup/${code}`, {
    body: { favorites: ["from-a"] },
    headers: { "If-Match": devA },
  });
  assert.equal(aWrite.status, 200);

  // B's write is rejected — it must re-pull, merge, retry (the real client flow).
  const bWrite = await req("PUT", `/api/v1/backup/${code}`, {
    body: { favorites: ["from-b"] },
    headers: { "If-Match": devB },
  });
  assert.equal(bWrite.status, 409);
  const merged = (await req("GET", `/api/v1/backup/${code}`)).json;
  assert.deepEqual(merged.favorites, ["from-a"], "A's write is visible to B");

  const bRetry = await req("PUT", `/api/v1/backup/${code}`, {
    body: { favorites: ["from-a", "from-b"] },
    headers: { "If-Match": merged.revision },
  });
  assert.equal(bRetry.status, 200);
  const final = (await req("GET", `/api/v1/backup/${code}`)).json;
  assert.deepEqual(final.favorites.sort(), ["from-a", "from-b"], "union preserved");
});

test("rapid concurrent PUTs never corrupt the file", async () => {
  const code = "rapidfire";
  const rev0 = (await req("PUT", `/api/v1/backup/${code}`, { body: { favorites: [] }, headers: { "If-Match": "*" } })).json.revision;
  const reads = await Promise.all([1, 2, 3, 4, 5, 6, 7, 8].map(() => req("GET", `/api/v1/backup/${code}`)));
  const results = await Promise.all(
    reads.map((r, i) =>
      req("PUT", `/api/v1/backup/${code}`, {
        body: { favorites: [`item-${i}`] },
        headers: { "If-Match": r.json.revision },
      })
    )
  );
  const oks = results.filter((r) => r.status === 200).length;
  const conflicts = results.filter((r) => r.status === 409).length;
  assert.ok(oks >= 1, `at least one writer succeeded (${oks})`);
  assert.ok(conflicts >= 1, `at least one writer conflicted (${conflicts})`);
  // Files are encrypted envelopes, never plaintext personal data. The route
  // already proved the decrypted payload remained readable above.
  const raw = JSON.parse(fs.readFileSync(path.join(process.env.THRIVE_BACKUP_DIR, `${code}.json`), "utf-8"));
  assert.equal(raw.alg, "aes-256-gcm");
  assert.equal(raw.v, 1);
  assert.equal(typeof raw.iv, "string");
  assert.equal(typeof raw.tag, "string");
  assert.equal(typeof raw.data, "string");
  assert.equal(raw.favorites, undefined);
});

test("backup validates codes and bodies", async () => {
  assert.equal((await req("GET", "/api/v1/backup/ABC")).status, 400);
  assert.equal((await req("PUT", "/api/v1/backup/badcode!")).status, 400);
  const empty = await req("PUT", `/api/v1/backup/emptyx1`, {
    body: {},
    headers: { "If-Match": "*" },
  });
  assert.equal(empty.status, 400);
});

test("backup caps section sizes and sanitizes shapes", async () => {
  const code = "capsize1";
  // 600 valid short strings -> capped at 500.
  const huge = Array.from({ length: 600 }, (_, i) => `f${i}`);
  const r = await req("PUT", `/api/v1/backup/${code}`, {
    body: { favorites: huge },
    headers: { "If-Match": "*" },
  });
  assert.equal(r.status, 200);
  const read = await req("GET", `/api/v1/backup/${code}`);
  assert.equal(read.json.favorites.length, 500, "capped at 500");

  // Over-long strings are dropped, never stored.
  const tooLong = new Array(10).fill("x".repeat(100));
  await req("PUT", `/api/v1/backup/${code}`, {
    body: { favorites: tooLong },
    headers: { "If-Match": read.json.revision },
  });
  const read2 = await req("GET", `/api/v1/backup/${code}`);
  assert.equal(read2.json.favorites.length, 0, "over-long strings dropped");
});

// ---------------------------------------------------------------------------
// Admin feed: auth, strict schema, crash-resistance
// ---------------------------------------------------------------------------

const validDeal = (id) => ({
  id,
  store: "Test Store",
  productName: "Test Product " + id,
  category: "Grocery",
  price: 2.99,
  unitPrice: "1 lb",
  savingsPercent: 20,
  keywords: ["test"],
  endsInDays: 7,
  url: "https://example.com/p/" + id,
  urlVerified: true,
  imageUrl: "https://example.com/img/" + id + ".jpg",
});

test("admin deals: auth is enforced (token configured -> 401 on missing/wrong)", async () => {
  assert.equal((await req("POST", "/api/v1/deals", { body: [validDeal("d1")] })).status, 401);
  assert.equal(
    (await req("POST", "/api/v1/deals", { body: [validDeal("d1")], headers: { "x-thrive-admin-token": "wrong" } })).status,
    401
  );
});

test("admin deals: malformed payloads are rejected atomically with 400", async () => {
  const auth = { "x-thrive-admin-token": "test-admin-token" };
  // [{}] previously crashed GET /deals — now it is a 400 and health survives.
  const bad1 = await req("POST", "/api/v1/deals", { body: [{}], headers: auth });
  assert.equal(bad1.status, 400);
  assert.ok(Array.isArray(bad1.json.errors));

  const cases = [
    { body: [{ ...validDeal("x1"), price: -1 }], name: "negative price" },
    { body: [{ ...validDeal("x2"), price: NaN }], name: "NaN price" },
    { body: [{ ...validDeal("x3"), price: "2.99" }], name: "string price" },
    { body: [{ ...validDeal("x4"), savingsPercent: 150 }], name: "savings out of range" },
    { body: [{ ...validDeal("x5"), url: "javascript:alert(1)" }], name: "bad url" },
    { body: [{ ...validDeal("x6"), category: "a\u0000b" }], name: "control char category" },
    { body: [validDeal("dup"), validDeal("dup")], name: "duplicate ids" },
    { body: "not-an-array", name: "non-array body" },
    { body: new Array(5001).fill(0).map((_, i) => validDeal("bulk" + i)), name: "oversized payload" },
  ];
  for (const c of cases) {
    const r = await req("POST", "/api/v1/deals", { body: c.body, headers: auth });
    assert.equal(r.status, 400, `${c.name} must be 400`);
  }
  // Health is still up after every malformed attempt.
  assert.equal((await req("GET", "/api/v1/health")).status, 200);
});

test("admin deals: a valid payload is stored and served", async () => {
  const auth = { "x-thrive-admin-token": "test-admin-token" };
  const ok = await req("POST", "/api/v1/deals", {
    body: [validDeal("good1"), { ...validDeal("good2"), category: "Tech" }],
    headers: auth,
  });
  assert.equal(ok.status, 200);
  assert.equal(ok.json.deals, 2);

  const cat = await req("GET", "/api/v1/deals?category=Tech");
  assert.equal(cat.status, 200);
  assert.equal(cat.json.deals.length, 1);
  assert.equal(cat.json.deals[0].id, "good2");
  // The old crash path (missing category) can no longer terminate the process.
  assert.equal((await req("GET", "/api/v1/health")).status, 200);
});

test("live verified deals appear first in the coupons feed as real links", async () => {
  const auth = { "x-thrive-admin-token": "test-admin-token" };
  const live = [
    {
      ...validDeal("kroger-live-1"),
      store: "Kroger",
      productName: "Kroger Whole Milk, 1 gal",
      url: "https://www.kroger.com/p/kroger-whole-milk-1-gal/0000111109333",
      urlVerified: true,
      estimated: false,
      regularPrice: 3.49,
      price: 2.99,
      imageUrl: "https://www.kroger.com/product/images/medium/front/0000111109333",
      brand: "Kroger",
      size: "1 gal",
    },
    { ...validDeal("kroger-live-2"), store: "Kroger", productName: "Eggland's Best Eggs", url: "https://www.kroger.com/p/eggland-s-best-eggs/0071514111357", urlVerified: true, estimated: false, price: 6.99 },
  ];
  const ok = await req("POST", "/api/v1/deals", { body: live, headers: auth });
  assert.equal(ok.status, 200);

  const coupons = await req("GET", "/api/v1/coupons");
  assert.equal(coupons.status, 200);
  const list = coupons.json.coupons;
  assert.ok(list.length >= 2, "feed should include the live deals");
  const first = list[0];
  assert.equal(first.id, "kroger-live-1");
  assert.equal(first.urlVerified, true);
  assert.equal(first.url, "https://www.kroger.com/p/kroger-whole-milk-1-gal/0000111109333");
  assert.equal(first.store, "Kroger");
  assert.equal(first.title, "Kroger Whole Milk, 1 gal");
  assert.equal(first.priceBefore, 3.49); // honest before-price from the promo
  assert.equal(first.priceAfter, 2.99);
  assert.equal(first.imageUrl, "https://www.kroger.com/product/images/medium/front/0000111109333");
  assert.equal(first.estimated, false);
  // The bundled catalog stays available too (unverified items remain, the app
  // decides availability) — but the live deal must be first.
  assert.ok(list.some((c) => c.id === "kroger-live-2"));
});

test("malformed JSON body returns 400 and health survives", async () => {
  const r = await req("POST", "/api/v1/deals", { body: "{ not json", headers: { "x-thrive-admin-token": "test-admin-token", "Content-Type": "application/json" } });
  assert.equal(r.status, 400);
  assert.equal((await req("GET", "/api/v1/health")).status, 200);
});

test("unknown routes 404 and health survives", async () => {
  assert.equal((await req("GET", "/api/v1/nope")).status, 404);
  assert.equal((await req("GET", "/api/v1/health")).status, 200);
});

// ---------------------------------------------------------------------------
// Location-aware sync: location payloads never pollute the shared cache
// ---------------------------------------------------------------------------

test("location-tagged sync never leaks into a later location-free request", async () => {
  // A location-aware sync first: the payload carries the location block and
  // nearby-stores summary.
  const loc = await req("GET", "/api/v1/sync?lat=47.62&lng=-122.33");
  assert.equal(loc.status, 200);
  assert.ok(loc.json.location, "location-aware sync includes a location block");
  assert.ok(Array.isArray(loc.json.location.nearbyStores) && loc.json.location.nearbyStores.length > 0);

  // Regression: the NEXT request without coordinates must be a clean,
  // location-free payload — never the cached location-tagged one. Before the
  // fix, syncPayload overwrote the shared location-free cache with the
  // location payload, so this request returned another user's location block
  // and distance-annotated deals.
  const plain = await req("GET", "/api/v1/sync");
  assert.equal(plain.status, 200);
  assert.equal(plain.json.location, null, "location-free sync must not carry a location block");
  // Deals may carry storeDistanceMi: null ("no distance known") but never an
  // actual number — a real distance would mean the location-tagged payload
  // leaked into this request.
  assert.ok(
    plain.json.deals.every((d) => d.storeDistanceMi == null),
    "location-free deals must not be distance-annotated"
  );
  assert.ok(
    plain.json.coupons.every((c) => c.storeDistanceMi == null),
    "location-free coupons must not be distance-annotated"
  );

  // Different location bucket stays independent too.
  const loc2 = await req("GET", "/api/v1/sync?lat=40.71&lng=-74.01");
  assert.equal(loc2.status, 200);
  assert.ok(loc2.json.location, "second bucket still location-aware");
  const plain2 = await req("GET", "/api/v1/sync");
  assert.equal(plain2.json.location, null);
});
