"use strict";

const { test, before, after } = require("node:test");
const assert = require("node:assert/strict");
const fs = require("fs");
const os = require("os");
const path = require("path");
const { parseEncryptionKey } = require("../src/accountStore");

const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), "thrive-google-test-"));
process.env.THRIVE_BACKUP_DIR = path.join(tmpDir, "backups");
process.env.THRIVE_ACCOUNT_DB = path.join(tmpDir, "accounts.sqlite");
process.env.THRIVE_DATA_ENCRYPTION_KEY = Buffer.alloc(32, 7).toString("base64");
process.env.THRIVE_GOOGLE_TEST_MODE = "1";
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
  if (app.locals.accountStore) app.locals.accountStore.close();
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
  try { json = await res.json(); } catch { /* non-JSON response */ }
  return { status: res.status, json, headers: res.headers };
}

const GOOGLE_TOKEN = "fake-google-id-token-for-tests";
let auth;
let revision;

test("account encryption rejects missing and undersized keys", () => {
  assert.throws(() => parseEncryptionKey(undefined), /THRIVE_DATA_ENCRYPTION_KEY/);
  assert.throws(() => parseEncryptionKey(Buffer.alloc(16).toString("base64")), /exactly 32 bytes/);
  assert.equal(parseEncryptionKey(Buffer.alloc(32, 1).toString("base64")).length, 32);
});

test("auth/google rejects a missing idToken", async () => {
  assert.equal((await req("POST", "/api/v1/auth/google", { body: {} })).status, 401);
});

test("tunnel-forwarded clients receive independent auth rate limits", async () => {
  for (let i = 1; i <= 31; i += 1) {
    const response = await req("POST", "/api/v1/auth/google", {
      headers: { "X-Forwarded-For": `198.51.100.${i}` },
      body: {},
    });
    assert.equal(response.status, 401);
  }
  const differentClient = await req("POST", "/api/v1/auth/refresh", {
    headers: { "X-Forwarded-For": "203.0.113.44" },
    body: { refreshToken: "invalid" },
  });
  assert.equal(differentClient.status, 401);
});

test("auth/google exchanges Google identity for opaque Thrive tokens", async () => {
  const response = await req("POST", "/api/v1/auth/google", { body: { idToken: GOOGLE_TOKEN } });
  assert.equal(response.status, 200);
  assert.equal(response.json.ok, true);
  assert.equal(response.json.sub, "11112222333344445555");
  assert.equal(response.json.email, "test@example.com");
  assert.match(response.json.accountKey, /^g[0-9a-f]{15}$/);
  assert.match(response.json.accessToken, /^[A-Za-z0-9_-]{40,}$/);
  assert.match(response.json.refreshToken, /^[A-Za-z0-9_-]{40,}$/);
  assert.ok(response.json.accessTokenExpiresAt > Date.now());
  assert.equal(response.json.tokenType, "Bearer");
  assert.equal(response.headers.get("cache-control"), "no-store");
  assert.equal(response.headers.get("x-content-type-options"), "nosniff");
  auth = response.json;
});

test("account backup rejects missing and Google ID Bearer tokens", async () => {
  assert.equal((await req("GET", "/api/v1/account/backup")).status, 401);
  assert.equal((await req("GET", "/api/v1/account/backup", {
    headers: { Authorization: `Bearer ${GOOGLE_TOKEN}` },
  })).status, 401);
});

test("encrypted account backup round-trips legacy and household state", async () => {
  const headers = { Authorization: `Bearer ${auth.accessToken}` };
  const create = await req("PUT", "/api/v1/account/backup", {
    headers: { ...headers, "If-Match": "*" },
    body: {
      favorites: ["c1", "c2"],
      recipeFavorites: ["recipe-1"],
      pantry: [{ id: "p1", name: "Secret Rice", category: "Pantry", location: "pantry", quantity: 1, unit: "bag" }],
      budget: { budget: 75, people: 2, items: [] },
      householdProfile: {
        appliances: ["Oven", "Air fryer", "Unknown device"],
        budgetCadence: "MONTHLY", budgetAmount: 360.129,
        householdSize: 3, onboardingVersion: 1, onboardingCompletedAt: 1787620042000,
      },
      seenDealIds: ["deal-1", "deal-2", "deal-1"],
      feedRevision: "feed-2026-08-24",
      deletedFavoriteIds: ["old-favorite"],
      deletedPantryItemIds: ["old-pantry"],
    },
  });
  assert.equal(create.status, 200);
  revision = create.json.revision;
  assert.ok(revision);

  const read = await req("GET", "/api/v1/account/backup", { headers });
  assert.equal(read.status, 200);
  assert.deepEqual(read.json.favorites, ["c1", "c2"]);
  assert.deepEqual(read.json.recipeFavorites, ["recipe-1"]);
  assert.equal(read.json.pantry[0].name, "Secret Rice");
  assert.deepEqual(read.json.householdProfile.appliances, ["Oven", "Air fryer"]);
  assert.equal(read.json.householdProfile.budgetCadence, "MONTHLY");
  assert.equal(read.json.householdProfile.budgetAmount, 360.13);
  assert.equal(read.json.householdProfile.onboardingCompletedAt, 1787620042000);
  assert.deepEqual(read.json.seenDealIds, ["deal-1", "deal-2"]);
  assert.equal(read.json.feedRevision, "feed-2026-08-24");
  assert.deepEqual(read.json.deletedFavoriteIds, ["old-favorite"]);
  assert.deepEqual(read.json.deletedPantryItemIds, ["old-pantry"]);
  assert.equal(read.json.revision, revision);
});

test("account backup preserves optimistic concurrency and section merging", async () => {
  const headers = { Authorization: `Bearer ${auth.accessToken}` };
  const stale = await req("PUT", "/api/v1/account/backup", {
    headers: { ...headers, "If-Match": "wrong-revision" }, body: { favorites: ["c3"] },
  });
  assert.equal(stale.status, 409);
  assert.equal(stale.json.currentRevision, revision);
  const update = await req("PUT", "/api/v1/account/backup", {
    headers: { ...headers, "If-Match": revision }, body: { favorites: ["c3"] },
  });
  assert.equal(update.status, 200);
  assert.notEqual(update.json.revision, revision);
  revision = update.json.revision;
  const read = await req("GET", "/api/v1/account/backup", { headers });
  assert.deepEqual(read.json.favorites, ["c3"]);
  assert.deepEqual(read.json.recipeFavorites, ["recipe-1"]);
  assert.equal(read.json.householdProfile.onboardingVersion, 1);
  assert.deepEqual(read.json.seenDealIds, ["deal-1", "deal-2"]);
});

test("SQLite stores tokens as hashes and profile/state as encrypted blobs", () => {
  const db = app.locals.accountStore.rawDatabaseForTests();
  const account = db.prepare("SELECT * FROM accounts").get();
  const state = db.prepare("SELECT * FROM account_state").get();
  const sessions = db.prepare("SELECT token_hash FROM sessions").all();
  assert.equal(account.google_sub_hash.length, 64);
  assert.ok(account.profile_ciphertext instanceof Uint8Array);
  assert.ok(state.state_ciphertext instanceof Uint8Array);
  assert.ok(!Buffer.from(account.profile_ciphertext).toString("utf8").includes("test@example.com"));
  assert.ok(!Buffer.from(state.state_ciphertext).toString("utf8").includes("Secret Rice"));
  assert.ok(sessions.every((row) => row.token_hash !== auth.accessToken && row.token_hash !== auth.refreshToken));
  assert.ok(sessions.every((row) => /^[0-9a-f]{64}$/.test(row.token_hash)));
});

test("refresh rotation invalidates old access and refresh tokens", async () => {
  const refresh = await req("POST", "/api/v1/auth/refresh", { body: { refreshToken: auth.refreshToken } });
  assert.equal(refresh.status, 200);
  assert.notEqual(refresh.json.accessToken, auth.accessToken);
  assert.notEqual(refresh.json.refreshToken, auth.refreshToken);
  assert.ok(refresh.json.accessTokenExpiresAt > Date.now());
  assert.equal((await req("GET", "/api/v1/account/backup", {
    headers: { Authorization: `Bearer ${auth.accessToken}` },
  })).status, 401);
  assert.equal((await req("POST", "/api/v1/auth/refresh", { body: { refreshToken: auth.refreshToken } })).status, 401);
  auth = { ...auth, ...refresh.json };
});

test("logout revokes the complete session family", async () => {
  assert.equal((await req("POST", "/api/v1/auth/logout", {
    headers: { Authorization: `Bearer ${auth.accessToken}` }, body: { refreshToken: auth.refreshToken },
  })).status, 200);
  assert.equal((await req("GET", "/api/v1/account/backup", {
    headers: { Authorization: `Bearer ${auth.accessToken}` },
  })).status, 401);
  assert.equal((await req("POST", "/api/v1/auth/refresh", { body: { refreshToken: auth.refreshToken } })).status, 401);
});

test("account backup rejects malformed extended state", async () => {
  const signedIn = await req("POST", "/api/v1/auth/google", { body: { idToken: GOOGLE_TOKEN } });
  const headers = { Authorization: `Bearer ${signedIn.json.accessToken}`, "If-Match": revision };
  assert.equal((await req("PUT", "/api/v1/account/backup", { headers, body: { householdProfile: [] } })).status, 400);
  assert.equal((await req("PUT", "/api/v1/account/backup", { headers, body: { feedRevision: "x".repeat(129) } })).status, 400);
});

test("authenticated account deletion removes profile state and every session", async () => {
  const signedIn = await req("POST", "/api/v1/auth/google", {
    headers: { "X-Forwarded-For": "203.0.113.99" },
    body: { idToken: GOOGLE_TOKEN },
  });
  assert.equal(signedIn.status, 200);
  const accessToken = signedIn.json.accessToken;
  const deleted = await req("DELETE", "/api/v1/account", {
    headers: { Authorization: `Bearer ${accessToken}` },
  });
  assert.equal(deleted.status, 200);
  assert.equal(deleted.json.deleted, true);
  assert.equal((await req("GET", "/api/v1/account/backup", {
    headers: { Authorization: `Bearer ${accessToken}` },
  })).status, 401);
  const db = app.locals.accountStore.rawDatabaseForTests();
  assert.equal(db.prepare("SELECT COUNT(*) AS count FROM accounts").get().count, 0);
  assert.equal(db.prepare("SELECT COUNT(*) AS count FROM account_state").get().count, 0);
  assert.equal(db.prepare("SELECT COUNT(*) AS count FROM sessions").get().count, 0);
});

test("health still answers after auth and encrypted account traffic", async () => {
  const response = await req("GET", "/api/v1/health");
  assert.equal(response.status, 200);
  assert.equal(response.json.ok, true);
});
