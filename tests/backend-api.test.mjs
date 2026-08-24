import assert from "node:assert/strict";
import test from "node:test";

const workerUrl = new URL("../dist/server/index.js", import.meta.url);
workerUrl.searchParams.set("backend-test", `${process.pid}-${Date.now()}`);
const { default: worker } = await import(workerUrl.href);

const runtimeEnv = {
  ASSETS: {
    fetch: async () => new Response("Not found", { status: 404 }),
  },
};

const context = {
  waitUntil() {},
  passThroughOnException() {},
};

function fetchApi(path, init = {}) {
  const headers = new Headers(init.headers);
  if (init.body && !headers.has("content-type")) headers.set("content-type", "application/json");
  return worker.fetch(new Request(`http://localhost${path}`, { ...init, headers }), runtimeEnv, context);
}

test("cacheable feeds return stable ETags and support conditional requests", async () => {
  const first = await fetchApi("/api/v1/sync");
  assert.equal(first.status, 200);
  const etag = first.headers.get("etag");
  assert.ok(etag);
  const payload = await first.json();
  assert.match(payload.generatedAt, /^\d{4}-\d{2}-\d{2}T00:00:00\.000Z$/);
  assert.ok(payload.deals.length >= 30);

  const conditional = await fetchApi("/api/v1/sync", { headers: { "if-none-match": `W/${etag}` } });
  assert.equal(conditional.status, 304);
  assert.equal(await conditional.text(), "");
});

test("feed query validation rejects invalid limits", async () => {
  const invalid = await fetchApi("/api/v1/deals?limit=not-a-number");
  assert.equal(invalid.status, 400);
  assert.equal((await invalid.json()).error.code, "VALIDATION_ERROR");

  const valid = await fetchApi("/api/v1/coupons?limit=2");
  assert.equal(valid.status, 200);
  assert.equal((await valid.json()).coupons.length, 2);
});

test("weekly plan validates nights and produces a bounded plan", async () => {
  const invalid = await fetchApi("/api/v1/weekly-plan", {
    method: "POST",
    body: JSON.stringify({ pantry: [], budget: 100, people: 4, nights: 2.5 }),
  });
  assert.equal(invalid.status, 400);
  assert.equal((await invalid.json()).error.code, "VALIDATION_ERROR");

  const valid = await fetchApi("/api/v1/weekly-plan", {
    method: "POST",
    body: JSON.stringify({ pantry: [{ name: "chicken breast" }, { name: "rice" }], budget: 100, people: 4, nights: 3 }),
  });
  assert.equal(valid.status, 200);
  const payload = await valid.json();
  assert.equal(payload.plan.nightsCount, 3);
  assert.equal(payload.plan.people, 4);
  assert.equal(payload.plan.budget, 100);
});

test("meal and trip endpoints enforce bounded numeric inputs", async () => {
  const invalidMeals = await fetchApi("/api/v1/meal-suggestions", {
    method: "POST",
    body: JSON.stringify({ pantry: [], limit: 11 }),
  });
  assert.equal(invalidMeals.status, 400);

  const invalidTrip = await fetchApi("/api/v1/trip-plan", {
    method: "POST",
    body: JSON.stringify({ items: [], budget: 100, people: 0 }),
  });
  assert.equal(invalidTrip.status, 400);

  const validTrip = await fetchApi("/api/v1/trip-plan", {
    method: "POST",
    body: JSON.stringify({ items: [{ id: "milk", name: "Milk", category: "Dairy", quantity: 2.9, estPrice: 4 }], budget: 100, people: 2 }),
  });
  assert.equal(validTrip.status, 200);
  const payload = await validTrip.json();
  assert.equal(payload.plan.items[0].item.quantity, 2);
  assert.ok(Number.isFinite(payload.plan.totalAfter));
});

test("state API returns structured validation and storage errors without a D1 binding", async () => {
  const invalid = await fetchApi("/api/v1/state?deviceId=short");
  assert.equal(invalid.status, 400);
  assert.equal((await invalid.json()).error.code, "VALIDATION_ERROR");

  const unavailable = await fetchApi("/api/v1/state?deviceId=local-device-1234567890");
  assert.equal(unavailable.status, 503);
  assert.equal((await unavailable.json()).error.code, "STORAGE_UNAVAILABLE");
});
