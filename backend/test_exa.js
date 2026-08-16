"use strict";

/**
 * Tests ExaService (web discovery) against a stubbed fetch: success shape,
 * missing key (disabled), malformed queries (400), provider failure, timeout,
 * rate limiting, daily spend cap, unsafe-URL filtering, and the invariant
 * that results are ALWAYS labeled verified:false / web-discovery (Exa is a
 * discovery lead, never a verified price/deal).
 *
 * Run:  node test_exa.js
 */

const { ExaService } = require("./src/exaService");

let failures = 0;
function check(name, cond, extra) {
  if (cond) {
    console.log(`  ok  ${name}`);
  } else {
    failures++;
    console.error(`FAIL  ${name}${extra ? " — " + extra : ""}`);
  }
}

function exaJson(body, status = 200) {
  return { ok: status >= 200 && status < 300, status, json: async () => body };
}

const SAMPLE_RESULT = {
  title: "Kroger Weekly Ad — Chicken Breast on Sale",
  url: "https://www.kroger.com/weeklyad",
  publishedDate: "2026-08-14T00:00:00Z",
  highlights: ["Boneless skinless chicken breast $1.99/lb this week"],
};

async function main() {
  console.log("ExaService tests");

  // ---- disabled without a key ------------------------------------------------
  delete process.env.EXA_API_KEY;
  const noKey = new ExaService();
  check("disabled without EXA_API_KEY", noKey.enabled === false);
  const noKeyRes = await noKey.search("chicken");
  check("search without key returns honest empty", noKeyRes.results.length === 0);
  check("empty response notes the missing config", /not configured/.test(noKeyRes.note));

  // ---- query validation -------------------------------------------------------
  process.env.EXA_API_KEY = "test-key";
  const svc = new ExaService();
  for (const bad of ["", "a", "  x  ", "a".repeat(121), "bad\u0000query"]) {
    let threw = false;
    try {
      await svc.search(bad);
    } catch (e) {
      threw = e && e.status === 400;
    }
    check(`malformed query rejected with 400 (${JSON.stringify(String(bad).slice(0, 8))})`, threw);
  }
  check("service-level query validator accepts valid query", ExaService.validateQuery("chicken breast") === "chicken breast");

  // ---- success shape ------------------------------------------------------------
  global.fetch = async (url, opts) => {
    check("calls the Exa search endpoint", String(url) === "https://api.exa.ai/search");
    check("sends x-api-key header", String(opts.headers["x-api-key"]) === "test-key");
    const body = JSON.parse(opts.body);
    check("sends query, numResults, highlights", body.query.includes("chicken") && body.numResults === 3 && body.contents && body.contents.highlights === true);
    return exaJson({ results: [SAMPLE_RESULT] });
  };
  const svc2 = new ExaService();
  const good = await svc2.search("chicken breast", { limit: 3 });
  check("success returns the result", good.results.length === 1);
  const r = good.results[0];
  check("result carries title/url/excerpt", r.title && r.url && r.excerpt);
  check("result is NEVER marked verified", r.verified === false);
  check("result labeled web-discovery", r.kind === "web-discovery");
  check("confidence is a bounded heuristic", typeof r.confidence === "number" && r.confidence >= 0 && r.confidence <= 1);

  // ---- cache: second identical query does not hit the network -------------------
  let calls = 0;
  global.fetch = async () => { calls++; return exaJson({ results: [SAMPLE_RESULT] }); };
  const cachedSvc = new ExaService();
  const c1 = await cachedSvc.search("milk", { limit: 2 });
  const c2 = await cachedSvc.search("milk", { limit: 2 });
  check("identical query served from cache (no second network call)", c1.results.length === 1 && c2.results.length === 1 && calls === 1, `calls=${calls}`);
  check("cached response flags cached:true", c2.cached === true);

  // ---- unsafe URLs are dropped ---------------------------------------------------
  global.fetch = async () => exaJson({
    results: [
      SAMPLE_RESULT,
      { title: "bad", url: "javascript:alert(1)" },
      { title: "bad2", url: "file:///etc/passwd" },
      { title: "bad3", url: "data:text/html,<script>" },
      { title: "bad4", url: "http://user:pass@evil.com/x" },
      { title: "bad5", url: "not a url" },
    ],
  });
  const safe = await new ExaService().search("eggs", { limit: 6 });
  check("unsafe URLs filtered out", safe.results.length === 1 && safe.results[0].url === SAMPLE_RESULT.url, `got ${safe.results.length}`);

  // ---- provider failures degrade to honest empty ---------------------------------
  global.fetch = async () => exaJson({ error: "boom" }, 500);
  const fail = await new ExaService().search("pasta", { limit: 3 });
  check("HTTP 500 degrades to honest empty", fail.results.length === 0 && /error/i.test(fail.note));

  global.fetch = async () => { throw new Error("net down"); };
  const net = await new ExaService().search("pasta", { limit: 3 });
  check("network failure degrades to honest empty", net.results.length === 0);

  global.fetch = async () => { throw Object.assign(new Error("timeout"), { name: "TimeoutError" }); };
  const tmo = await new ExaService().search("pasta", { limit: 3 });
  check("timeout degrades to honest empty", tmo.results.length === 0 && /timed out/i.test(tmo.note));

  // ---- rate limiting / spend caps -------------------------------------------------
  const rl = new ExaService();
  rl.ratePerMin = 2;
  rl.maxDaily = 1000;
  global.fetch = async () => exaJson({ results: [SAMPLE_RESULT] });
  await rl.search("one", { limit: 1 });
  await rl.search("two", { limit: 1 });
  let rlThrew = false;
  try {
    await rl.search("three", { limit: 1 });
  } catch (e) {
    rlThrew = e && e.status === 429;
  }
  check("rate limit returns 429", rlThrew);

  const spend = new ExaService();
  spend.ratePerMin = 100;
  spend.maxDaily = 2;
  global.fetch = async () => exaJson({ results: [SAMPLE_RESULT] });
  await spend.search("alpha", { limit: 1 });
  await spend.search("beta", { limit: 1 });
  let spendThrew = false;
  try {
    await spend.search("gamma", { limit: 1 });
  } catch (e) {
    spendThrew = e && e.status === 429;
  }
  check("daily spend cap returns 429", spendThrew);

  // ---- live smoke test against the REAL key (skipped when unset) -----------------
  if (process.env.EXA_SMOKE === "1" && process.env.EXA_API_KEY) {
    const live = new ExaService();
    const res = await live.search("kroger weekly ad chicken", { limit: 3 });
    check("live search returns results", res.results.length > 0, `got ${res.results.length}`);
  } else {
    console.log("  skip  live smoke test (set EXA_SMOKE=1 to run against the real key)");
  }

  global.fetch = undefined;
  console.log(failures === 0 ? "\nAll Exa tests passed." : `\n${failures} FAILED`);
  process.exit(failures === 0 ? 0 : 1);
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
