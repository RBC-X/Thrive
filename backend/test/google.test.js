"use strict";

const { test, before, after } = require("node:test");
const assert = require("node:assert/strict");
const fs = require("fs");
const os = require("os");
const path = require("path");

// Isolated data dir + random port. The test-only Google override short-circuits
// token verification so the whole flow is testable without network access.
const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), "thrive-google-test-"));
process.env.THRIVE_BACKUP_DIR = path.join(tmpDir, "backups");
process.env.THRIVE_GOOGLE_TEST_SUB = "11112222333344445555";

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
  return { status: res.status, json, headers: res.headers };
}

const TOKEN = "fake-google-id-token-for-tests";

test("auth/google rejects a missing idToken", async () => {
  const r = await req("POST", "/api/v1/auth/google", { body: {} });
  assert.equal(r.status, 401);
});

test("auth/google returns profile + accountKey", async () => {
  const r = await req("POST", "/api/v1/auth/google", { body: { idToken: TOKEN } });
  assert.equal(r.status, 200);
  assert.equal(r.json.ok, true);
  assert.equal(r.json.sub, "11112222333344445555");
  assert.equal(r.json.email, "test@example.com");
  // 16 hex chars, stable, and NOT the raw sub.
  assert.match(r.json.accountKey, /^g[0-9a-f]{15}$/);
});

test("account backup requires a Bearer token", async () => {
  const r = await req("GET", "/api/v1/account/backup");
  assert.equal(r.status, 401);
});

test("account backup round-trip with revisions + If-Match", async () => {
  const headers = { Authorization: `Bearer ${TOKEN}` };
  const create = await req("PUT", "/api/v1/account/backup", {
    body: { favorites: ["c1", "c2"] },
    headers: { ...headers, "If-Match": "*" },
  });
  assert.equal(create.status, 200);
  const rev1 = create.json.revision;
  assert.ok(rev1);

  const read = await req("GET", "/api/v1/account/backup", { headers });
  assert.equal(read.status, 200);
  assert.deepEqual(read.json.favorites, ["c1", "c2"]);
  assert.equal(read.json.revision, rev1);

  // Stale write must conflict (concurrency protection, same as code backups).
  const stale = await req("PUT", "/api/v1/account/backup", {
    body: { favorites: ["c3"] },
    headers: { ...headers, "If-Match": "wrong-revision" },
  });
  assert.equal(stale.status, 409);

  const update = await req("PUT", "/api/v1/account/backup", {
    body: { favorites: ["c3"] },
    headers: { ...headers, "If-Match": rev1 },
  });
  assert.equal(update.status, 200);
  const rev2 = update.json.revision;
  assert.notEqual(rev2, rev1);

  const merged = await req("GET", "/api/v1/account/backup", { headers });
  assert.deepEqual(merged.json.favorites, ["c3"]);
  assert.equal(merged.json.revision, rev2);
});

test("account backup stores under a key derived from sub, not the code namespace", async () => {
  const headers = { Authorization: `Bearer ${TOKEN}` };
  await req("PUT", "/api/v1/account/backup", {
    body: { pantry: [{ id: "p1", name: "Rice", category: "Pantry", location: "pantry", quantity: 1, unit: "bag" }] },
    headers: { ...headers, "If-Match": "*" },
  });
  const files = fs.readdirSync(path.join(tmpDir, "backups"));
  assert.equal(files.length, 1);
  // The key is g + 15 hex chars — never the raw Google sub.
  assert.match(files[0], /^g[0-9a-f]{15}\.json$/);
});

test("account backup rejects malformed section data with 400", async () => {
  const headers = { Authorization: `Bearer ${TOKEN}` };
  const bad = await req("PUT", "/api/v1/account/backup", {
    body: { favorites: "not-an-array", pantry: 42 },
    headers: { ...headers, "If-Match": "*" },
  });
  // favorites/pantry not arrays => hasFavorites/hasPantry false and budget absent
  // => 400 "body must include favorites, pantry, or budget"
  assert.equal(bad.status, 400);
});

test("health still answers after auth + account backup traffic", async () => {
  const r = await req("GET", "/api/v1/health");
  assert.equal(r.status, 200);
  assert.equal(r.json.ok, true);
});
