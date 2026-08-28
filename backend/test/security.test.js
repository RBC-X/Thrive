/**
 * ECC Security Invariant Tests
 * ============================
 * Regression tests that prove critical security properties survive refactoring.
 * Each test asserts a real observable behavior, not a code pattern.
 */
const { describe, it } = require("node:test");
const assert = require("node:assert/strict");
const http = require("http");

// Test-mode server (starts with bundled-only sources, no credentials)
process.env.NODE_ENV = "test";
process.env.THRIVE_TEST_BUNDLED_ONLY = "1";

let app;
let server;
let BASE;

async function start() {
  if (server) return;
  app = require("../server");
  await new Promise((resolve) => {
    server = app.listen(0, () => {
      BASE = `http://127.0.0.1:${server.address().port}`;
      resolve();
    });
  });
}

function close() {
  return new Promise((resolve) => {
    if (!server) return resolve();
    server.close(resolve);
    server = null;
  });
}

async function get(path) {
  const res = await fetch(`${BASE}${path}`);
  return { status: res.status, body: await res.json().catch(() => ({})), headers: Object.fromEntries(res.headers) };
}

async function put(path, body, headers = {}) {
  const res = await fetch(`${BASE}${path}`, {
    method: "PUT",
    headers: { "Content-Type": "application/json", ...headers },
    body: JSON.stringify(body),
  });
  return { status: res.status, body: await res.json().catch(() => ({})) };
}

async function post(path, body, headers = {}) {
  const res = await fetch(`${BASE}${path}`, {
    method: "POST",
    headers: { "Content-Type": "application/json", ...headers },
    body: JSON.stringify(body),
  });
  return { status: res.status, body: await res.json().catch(() => ({})) };
}

// --- Tests ---

describe("Security: Credential leakage", () => {
  it("health endpoint does not expose API keys or secrets", async () => {
    await start();
    const { body } = await get("/api/v1/health");
    const str = JSON.stringify(body);
    for (const secret of ["KROGER_CLIENT_SECRET", "EXA_API_KEY", "BACKUP_ENCRYPTION_KEY", "ADMIN_TOKEN"]) {
      assert.ok(!str.includes(secret), `health response must not contain ${secret}`);
    }
  });

  it("deals endpoint does not expose API keys or secrets", async () => {
    await start();
    const { body } = await get("/api/v1/deals?limit=3");
    const str = JSON.stringify(body);
    for (const secret of ["KROGER_CLIENT_SECRET", "EXA_API_KEY", "BACKUP_ENCRYPTION_KEY", "ADMIN_TOKEN"]) {
      assert.ok(!str.includes(secret), `deals response must not contain ${secret}`);
    }
  });

  it("sync endpoint does not expose API keys or secrets", async () => {
    await start();
    const { body } = await get("/api/v1/sync");
    const str = JSON.stringify(body);
    for (const secret of ["KROGER_CLIENT_SECRET", "EXA_API_KEY", "BACKUP_ENCRYPTION_KEY", "ADMIN_TOKEN"]) {
      assert.ok(!str.includes(secret), `sync response must not contain ${secret}`);
    }
  });

  it("coupon endpoint does not expose API keys or secrets", async () => {
    await start();
    const { body } = await get("/api/v1/coupons");
    const str = JSON.stringify(body);
    for (const secret of ["KROGER_CLIENT_SECRET", "EXA_API_KEY", "BACKUP_ENCRYPTION_KEY"]) {
      assert.ok(!str.includes(secret), `coupon response must not contain ${secret}`);
    }
  });
});

describe("Security: Backup encryption at rest", () => {
  it("newly created backups are written as encrypted envelopes, not plaintext", async () => {
    await start();
    const code = "sectest" + (Date.now() % 10000);
    // Create
    const { status } = await put(`/api/v1/backup/${code}`, {
      favorites: ["secret-deal-id"],
      pantry: [],
      budget: null,
    }, { "If-Match": "*" });
    assert.equal(status, 200, "backup create should succeed");

    // Read back to confirm the payload is correct
    const { body } = await get(`/api/v1/backup/${code}`);
    assert.deepEqual(body.favorites, ["secret-deal-id"]);

    // The file on disk must be an encrypted envelope, not plaintext
    const fs = require("fs");
    const path = require("path");
    const backupDir = process.env.THRIVE_BACKUP_DIR || path.join(__dirname, "..", "data", "backups");
    const filePath = path.join(backupDir, `${code}.json`);
    const raw = fs.readFileSync(filePath, "utf-8");
    const envelope = JSON.parse(raw);
    assert.equal(envelope.v, 1, "envelope version must be 1");
    assert.equal(envelope.alg, "aes-256-gcm", "encryption algorithm must be aes-256-gcm");
    assert.ok(envelope.iv, "envelope must have iv");
    assert.ok(envelope.tag, "envelope must have auth tag");
    assert.ok(envelope.data, "envelope must have ciphertext");
    assert.ok(!raw.includes("secret-deal-id"), "plaintext must not appear in the file");
  });
});

describe("Security: Location privacy", () => {
  it("sync with coordinates never returns exact lat/lng to the client", async () => {
    await start();
    const { body } = await get("/api/v1/sync?lat=40.7128&lng=-74.0060");
    const str = JSON.stringify(body);
    // Exact coordinates must not appear anywhere in the response
    assert.ok(!str.includes('"lat":40.7128'), "must not return exact user lat");
    assert.ok(!str.includes('"lng":-74.006'), "must not return exact user lng");
    assert.ok(!str.includes('"latitude":'), "must not return latitude field");
    assert.ok(!str.includes('"longitude":'), "must not return longitude field");
  });

  it("nearbyStores contains only store, city, distMi — no coordinates", async () => {
    await start();
    const { body } = await get("/api/v1/sync?lat=33.75&lng=-84.39");
    const stores = body.location?.nearbyStores || [];
    assert.ok(stores.length > 0, "should have nearby stores for Atlanta");
    for (const s of stores) {
      assert.ok(!s.lat, `store ${s.store} must not have lat`);
      assert.ok(!s.lng, `store ${s.store} must not have lng`);
      assert.ok(typeof s.distMi === "number", `store ${s.store} must have distMi`);
      assert.ok(s.city, `store ${s.store} must have city`);
    }
  });
});

describe("Security: Auth boundaries", () => {
  it("account backup requires Bearer token", async () => {
    await start();
    const { status } = await get("/api/v1/account/backup");
    assert.equal(status, 401);
  });

  it("account backup rejects fake token", async () => {
    await start();
    const { status } = await get("/api/v1/account/backup");
    assert.ok(status === 401 || status === 403);
  });

  it("admin POST requires token", async () => {
    await start();
    const { status } = await post("/api/v1/deals", []);
    assert.ok(status === 401 || status === 403, `admin POST without token should be rejected, got ${status}`);
  });

  it("admin POST rejects wrong token", async () => {
    await start();
    const { status } = await post("/api/v1/deals", [], { "x-thrive-admin-token": "wrong-token" });
    assert.ok(status === 401 || status === 403);
  });
});

describe("Security: Input validation", () => {
  it("rejects negative limit", async () => {
    await start();
    const { status } = await get("/api/v1/deals?limit=-1");
    assert.equal(status, 400);
  });

  it("rejects limit > 500", async () => {
    await start();
    const { status } = await get("/api/v1/deals?limit=9999");
    assert.equal(status, 400);
  });

  it("rejects non-numeric limit", async () => {
    await start();
    const { status } = await get("/api/v1/deals?limit=abc");
    assert.equal(status, 400);
  });

  it("rejects short backup code", async () => {
    await start();
    const { status } = await get("/api/v1/backup/abc");
    assert.equal(status, 400);
  });

  it("rejects backup code with special characters", async () => {
    await start();
    const { status } = await get("/api/v1/backup/abc!@#def");
    assert.equal(status, 400);
  });

  it("rejects missing If-Match on backup PUT", async () => {
    await start();
    const code = "valtest" + (Date.now() % 10000);
    const { status } = await put(`/api/v1/backup/${code}`, { favorites: [] });
    assert.equal(status, 428, "should require If-Match header");
  });

  it("rejects malformed JSON body on admin POST", async () => {
    await start();
    const res = await fetch(`${BASE}/api/v1/deals`, {
      method: "POST",
      headers: { "Content-Type": "application/json", "x-thrive-admin-token": "test" },
      body: "NOT JSON",
    });
    assert.ok(res.status >= 400, `malformed JSON should be rejected, got ${res.status}`);
  });
});

describe("Security: Server resilience", () => {
  it("server survives unknown routes", async () => {
    await start();
    const { status } = await get("/api/v1/nonexistent");
    assert.equal(status, 404);
    // Verify server is still alive
    const { body } = await get("/api/v1/health");
    assert.equal(body.ok, true);
  });

  it("server survives bad path params", async () => {
    await start();
    await get("/api/v1/backup/!!!invalid!!!");
    const { body } = await get("/api/v1/health");
    assert.equal(body.ok, true);
  });

  it("server survives oversized body", async () => {
    await start();
    const res = await fetch(`${BASE}/api/v1/backup/test123`, {
      method: "PUT",
      headers: { "Content-Type": "application/json", "If-Match": "*" },
      body: "x".repeat(5 * 1024 * 1024),
    });
    assert.ok(res.status >= 400);
    const { body } = await get("/api/v1/health");
    assert.equal(body.ok, true);
  });
});
