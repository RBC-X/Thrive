import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

let workerPromise;

async function worker() {
  if (!workerPromise) {
    const workerUrl = new URL("../dist/server/index.js", import.meta.url);
    workerUrl.searchParams.set("test", `${process.pid}-${Date.now()}`);
    workerPromise = import(workerUrl.href).then(module => module.default);
  }
  return workerPromise;
}

async function request(path = "/", options = {}) {
  const app = await worker();
  return app.fetch(
    new Request(`http://localhost${path}`, options),
    { ASSETS: { fetch: async () => new Response("Not found", { status: 404 }) } },
    { waitUntil() {}, passThroughOnException() {} },
  );
}

test("server-renders the real Thrive product shell", async () => {
  const response = await request("/", { headers: { accept: "text/html" } });
  assert.equal(response.status, 200);
  assert.match(response.headers.get("content-type") ?? "", /^text\/html\b/i);

  const html = await response.text();
  assert.match(html, /<title>Thrive/);
  assert.match(html, /Today(?:&apos;|&#x27;|')s pick/i);
  assert.match(html, /Deals for you/);
  assert.match(html, /aria-label="Main navigation"/);
  assert.match(html, />Savings</);
  assert.match(html, />Recipes</);
  assert.match(html, />Pantry</);
  assert.match(html, />Budget</);
  assert.doesNotMatch(html, /Your site is taking shape|Building your site|react-loading-skeleton/i);
  assert.doesNotMatch(html, /Sign in with ChatGPT/i);
  assert.doesNotMatch(html, /picsum\.photos/i);
});

test("core read-only and planning APIs return real structured data", async () => {
  const health = await request("/api/v1/health");
  assert.equal(health.status, 200);
  assert.equal((await health.json()).ok, true);

  const sync = await request("/api/v1/sync");
  assert.equal(sync.status, 200);
  const feed = await sync.json();
  assert.ok(feed.coupons.length > 20);
  assert.ok(feed.recipes.length > 10);
  assert.ok(feed.catalog.length > 20);

  const meals = await request("/api/v1/meal-suggestions", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ pantry: [{ name: "black beans", quantity: 2 }], limit: 3 }),
  });
  assert.equal(meals.status, 200);
  assert.ok((await meals.json()).suggestions.length > 0);

  const week = await request("/api/v1/weekly-plan", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ pantry: [], budget: 75, people: 4, focus: "balanced", nights: 7 }),
  });
  assert.equal(week.status, 200);
  assert.equal((await week.json()).plan.nightsCount, 7);
});

test("source preserves anonymous use and honest unavailable imagery", async () => {
  const [page, layout] = await Promise.all([
    readFile(new URL("../app/page.tsx", import.meta.url), "utf8"),
    readFile(new URL("../app/layout.tsx", import.meta.url), "utf8"),
  ]);
  assert.match(page, /No account required/);
  assert.match(page, /Product image unavailable/);
  assert.match(page, /aria-current/);
  assert.doesNotMatch(page, /picsum\.photos|foodImg|productImg/);
  assert.match(layout, /Thrive — Save smarter\. Eat better\./);
});
