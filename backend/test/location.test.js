"use strict";

const { test, before, after } = require("node:test");
const assert = require("node:assert/strict");
const fs = require("fs");
const os = require("os");
const path = require("path");

// Isolated data dir + random port, like backend.test.js.
const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), "thrive-loc-test-"));
process.env.THRIVE_BACKUP_DIR = path.join(tmpDir, "backups");
process.env.THRIVE_ADMIN_TOKEN = "loc-test-token";

// Disable live sources for deterministic tests: no Kroger credentials in CI.
process.env.KROGER_CLIENT_ID = "";
process.env.KROGER_CLIENT_SECRET = "";

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

async function req(method, pathname) {
  const res = await fetch(base + pathname, { method });
  let json = null;
  try {
    json = await res.json();
  } catch {
    /* non-JSON */
  }
  return { status: res.status, json };
}

// Cincinnati, OH — the registry has Kroger/Aldi branches there.
const CIN = "lat=39.10&lng=-84.51";

test("deals with location carry honest nearest-branch distances", async () => {
  const { status, json } = await req("GET", `/api/v1/deals?${CIN}`);
  assert.equal(status, 200);
  assert.ok(json.deals.length > 0);
  const withDist = json.deals.filter((d) => d.storeDistanceMi != null);
  assert.ok(withDist.length > 0, "at least some deals are distance-ranked");
  for (const d of withDist) {
    assert.ok(typeof d.storeDistanceMi === "number" && d.storeDistanceMi >= 0, `${d.id} distance is a sane number`);
  }
});

test("deals with location are sorted nearest-first", async () => {
  const { json } = await req("GET", `/api/v1/deals?${CIN}`);
  const dists = json.deals.map((d) => d.storeDistanceMi).filter((d) => d != null);
  for (let i = 1; i < dists.length; i++) {
    assert.ok(dists[i - 1] <= dists[i], `sorted ascending at index ${i}`);
  }
  // Unranked deals sort last.
  const firstNull = json.deals.findIndex((d) => d.storeDistanceMi == null);
  const lastRanked = json.deals.map((d) => d.storeDistanceMi).lastIndexOf(undefined);
  assert.ok(firstNull === -1 || firstNull > lastRanked, "unranked deals go last");
});

test("Cincinnati puts Kroger and Aldi nearest", async () => {
  const { json } = await req("GET", `/api/v1/deals?${CIN}&limit=30`);
  const first = json.deals.find((d) => d.storeDistanceMi != null);
  assert.ok(first, "some deal is ranked");
  assert.ok(
    first.storeDistanceMi <= 5,
    `nearest deal is a Cincinnati branch (got ${first.storeDistanceMi} mi @ ${first.store})`
  );
});

test("without location, deals are unranked (no distance labels)", async () => {
  const { json } = await req("GET", "/api/v1/deals?limit=30");
  assert.ok(json.deals.length > 0);
  for (const d of json.deals) {
    assert.equal(d.storeDistanceMi, undefined, "no distance label without location");
  }
});

test("bad coords are strict 400s, never NaN", async () => {
  for (const q of ["lat=abc&lng=-84", "lat=39&lng=abc", "lat=95&lng=0", "lat=0&lng=181", "lat=39.10", "lng=-84.51"]) {
    const { status, json } = await req("GET", `/api/v1/deals?${q}`);
    assert.equal(status, 400, `query "${q}" must 400`);
    assert.ok(json && json.error, `query "${q}" returns an error message`);
  }
});

test("full-precision GPS fixes are accepted (10-14 decimals)", async () => {
  // Real GPS fixes carry far more precision than a hand-typed test coordinate.
  const { status, json } = await req(
    "GET",
    "/api/v1/deals?lat=39.0990990990991&lng=-84.51137320567292"
  );
  assert.equal(status, 200, "precise real-world coords must not 400");
  assert.ok(json.deals.length > 0);
  assert.ok(json.deals.some((d) => d.storeDistanceMi != null), "still ranked");
});

test("sync payload carries location echo + nearby stores", async () => {
  const { status, json } = await req("GET", `/api/v1/sync?${CIN}`);
  assert.equal(status, 200);
  assert.ok(json.location, "payload echoes location");
  assert.ok(Math.abs(json.location.lat - 39.1) < 0.001 && Math.abs(json.location.lng - -84.51) < 0.001);
  assert.ok(json.location.nearbyStores.length > 0, "nearbyStores listed");
  assert.equal(json.location.nearbyStores[0].store, "Kroger");
  assert.ok(json.location.nearbyStores[0].distMi <= 5, "nearest store is genuinely near");
  // Deals inside the sync payload are distance-ranked too.
  const ranked = json.deals.filter((d) => d.storeDistanceMi != null);
  assert.ok(ranked.length > 0, "sync deals carry distances");
});
