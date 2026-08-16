"use strict";

/**
 * Tests TargetLiveSource against a recorded Target redsky response shape
 * (no credentials needed — the endpoint is public). Stubs global.fetch with
 * fixture responses and asserts the source normalizes them into Thrive Deals
 * with honest flags: only on-sale items, direct product links, real images,
 * deduped by TCIN, HTML entities unescaped.
 *
 * Run:  node test_target.js
 */

process.env.TARGET_TERMS = "headphones,laptop";

const { TargetLiveSource } = require("./src/sources");

let failures = 0;
function check(name, cond, extra) {
  if (cond) {
    console.log(`  ok  ${name}`);
  } else {
    failures++;
    console.error(`FAIL  ${name}${extra ? " — " + extra : ""}`);
  }
}

// ---- Fixtures (recorded shape of Target's redsky plp_search_v2) -----------

function targetJson(body, status = 200) {
  return {
    ok: status >= 200 && status < 300,
    status,
    json: async () => body,
  };
}

function product(tcin, title, price, buyUrl, extra = {}) {
  return {
    tcin,
    item: {
      product_description: { title },
      enrichment: {
        buy_url: buyUrl,
        image_info: {
          primary_image: { url: `https://target.scene7.com/is/image/Target/GUEST_${tcin}` },
        },
      },
      available_to_purchase_online: extra.available !== false,
      ...(extra.fulfillment ? { fulfillment: extra.fulfillment } : {}),
    },
    price,
  };
}

const SALE_HEADPHONES = product(
  "95017887",
  "Wired On-Ear Headphones - heyday&#8482;",
  { current_retail: 7.0, reg_retail: 10.0, save_percent: 30, formatted_current_price_type: "sale" },
  "https://www.target.com/p/wired-on-ear-headphones-heyday-8482/-/A-95017887"
);

const REGULAR_LAPTOP = product(
  "14061285",
  "Sony ZX Series Wired On Ear Headphones",
  { current_retail: 15.99, reg_retail: 15.99, save_percent: 0, formatted_current_price_type: "reg" },
  "https://www.target.com/p/sony-zx-series-wired-on-ear-headphones-white-mdr-zx110/-/A-14061285"
);

const NO_BUY_URL = product(
  "5550001",
  "Mystery Item",
  { current_retail: 5.0, reg_retail: 10.0, save_percent: 50 },
  null
);

const NOT_PURCHASABLE = product(
  "5550002",
  "Out of Stock Item",
  { current_retail: 9.99, reg_retail: 19.99, save_percent: 50 },
  "https://www.target.com/p/out-of-stock/-/A-5550002",
  { available: false }
);

const NO_PRICE = product(
  "5550003",
  "No Price Item",
  {},
  "https://www.target.com/p/no-price/-/A-5550003"
);

const calls = [];
global.fetch = async (url, opts) => {
  calls.push({ url, opts });
  // Same shape for every term so the test covers dedupe across terms.
  return targetJson({
    data: {
      search: {
        products: [
          SALE_HEADPHONES,
          REGULAR_LAPTOP,
          NO_BUY_URL,
          NOT_PURCHASABLE,
          NO_PRICE,
        ],
      },
    },
  });
};

async function main() {
  console.log("TargetLiveSource tests");
  // Start clean: a stale cache file from a previous run must not leak in.
  try {
    require("fs").unlinkSync(TargetLiveSource.CACHE_FILE);
  } catch (_) {}
  const src = new TargetLiveSource();

  check("enabled without credentials (keyless public endpoint)", src.enabled === true);

  const deals = await src.deals();

  // Exactly the one genuinely on-sale, purchasable, direct-link item,
  // deduped across both search terms.
  check("only the genuine sale item survives", deals.length === 1, `got ${deals.length}`);
  const d = deals[0];
  check("title HTML entities unescaped", d && d.productName === "Wired On-Ear Headphones - heyday™", d && d.productName);
  check("category is Tech", d && d.category === "Tech");
  check("sale price used", d && d.price === 7.0, JSON.stringify(d && d.price));
  check("regular price preserved as honest before-price", d && d.regularPrice === 10.0);
  check("savings percent computed", d && d.savingsPercent === 30, `got ${d && d.savingsPercent}`);
  check("verified direct product link", d && d.urlVerified === true && d.url === SALE_HEADPHONES.item.enrichment.buy_url, d && d.url);
  check("real product image carried", d && d.imageUrl === "https://target.scene7.com/is/image/Target/GUEST_95017887", d && d.imageUrl);
  check("live price flagged (not estimated)", d && d.estimated === false);
  check("id is stable and store-scoped", d && d.id === "target-95017887", d && d.id);

  // Fetch failure must never throw out of the source and must never claim
  // "live": it serves the last-good catalog honestly marked estimated.
  global.fetch = async () => ({ ok: false, status: 500, json: async () => ({}) });
  const fallback = await src.deals();
  check("network failure serves last-good catalog", fallback.length === 1, `got ${fallback.length}`);
  check("throttled data honestly marked estimated", fallback.every((x) => x.estimated === true));
  // A brand-new source with an empty cache degrades to an empty feed on failure.
  global.fetch = async () => ({ ok: false, status: 500, json: async () => ({}) });
  try { require("fs").unlinkSync(TargetLiveSource.CACHE_FILE); } catch (_) {}
  const empty = await new TargetLiveSource().deals();
  check("no cache + failure degrades to empty feed", Array.isArray(empty) && empty.length === 0, `got ${empty.length}`);
  // Akamai 403 trips the circuit breaker and serves the last-good cache.
  global.fetch = async () => ({ ok: false, status: 403, json: async () => ({}) });
  const blocked = await src.deals();
  check("403 trips breaker and serves cached deals", blocked.length === 1 && blocked.every((x) => x.estimated === true));
  // Empty search response (no products) also falls back, never invents deals.
  global.fetch = async (url, opts) => targetJson({ data: { search: {} } });
  try { require("fs").unlinkSync(TargetLiveSource.CACHE_FILE); } catch (_) {}
  const empty2 = await new TargetLiveSource().deals();
  check("empty search response degrades to empty feed", Array.isArray(empty2) && empty2.length === 0, `got ${empty2.length}`);

  console.log(failures === 0 ? "\nAll Target tests passed." : `\n${failures} FAILED`);
  process.exit(failures === 0 ? 0 : 1);
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
