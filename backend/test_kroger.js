"use strict";

/**
 * Tests KrogerLiveSource against a recorded Kroger API shape (no live
 * credentials needed). Stubs global.fetch with fixture responses and asserts
 * the source normalizes them into Thrive Deals with honest flags.
 *
 * Run:  node test_kroger.js
 */

process.env.KROGER_CLIENT_ID = "test-client";
process.env.KROGER_CLIENT_SECRET = "test-secret";
process.env.KROGER_ZIP = "45202";
process.env.KROGER_TERMS = "milk,eggs";

const { KrogerLiveSource } = require("./src/sources");

let failures = 0;
function check(name, cond, extra) {
  if (cond) {
    console.log(`  ok  ${name}`);
  } else {
    failures++;
    console.error(`FAIL  ${name}${extra ? " — " + extra : ""}`);
  }
}

// ---- Fixtures (recorded shape of the Kroger API) --------------------------

function krogerJson(body, status = 200) {
  return {
    ok: status >= 200 && status < 300,
    status,
    json: async () => body,
  };
}

const calls = [];
global.fetch = async (url, opts) => {
  calls.push({ url, opts });
  if (String(url).includes("/oauth2/token")) {
    return krogerJson({ access_token: "tok-123", expires_in: 1800 });
  }
  if (String(url).includes("/locations")) {
    return krogerJson({ data: [{ locationId: "LOC00001" }] });
  }
  if (String(url).includes("/products")) {
    return krogerJson({
      data: [
        {
          productId: "0001111046396",
          description: "Milk 1 Gallon",
          brand: "Kroger",
          productPageURI: "/p/kroger-milk-1-gallon/0001111046396?cid=tracking",
          categories: ["Dairy"],
          images: [{ sizes: [{ url: "https://img.kroger.com/milk.jpg" }] }],
          items: [{ size: "1 gal", price: { regular: 3.79, promo: 2.99 } }], // current API shape
        },
        {
          productId: "0001111088257",
          description: "Large Eggs 18 ct",
          brand: "Kroger",
          productPageURI: "/p/kroger-eggs-18-ct/0001111088257",
          categories: ["Dairy"],
          images: [{ sizes: [{ url: "https://img.kroger.com/eggs.jpg" }] }],
          items: [{ size: "18 ct", price: { regular: 4.29 } }], // no promo
        },
        {
          // Defensive: older regularPrice/promoPrice shape must still parse.
          productId: "0001111066666",
          description: "Old Shape Bread",
          brand: "Kroger",
          productPageURI: "/p/kroger-bread/0001111066666",
          categories: ["Bakery"],
          images: [{ sizes: [{ url: "https://img.kroger.com/bread.jpg" }] }],
          items: [{ size: "20 oz", price: { regularPrice: 3.19, promoPrice: 2.49 } }],
        },
      ],
    });
  }
  return krogerJson({ data: [] });
};

async function main() {
  console.log("KrogerLiveSource tests");
  const src = new KrogerLiveSource();

  // Fresh instance with credentials cleared -> source disables itself.
  const saved = { id: process.env.KROGER_CLIENT_ID, secret: process.env.KROGER_CLIENT_SECRET };
  delete process.env.KROGER_CLIENT_ID;
  delete process.env.KROGER_CLIENT_SECRET;
  check("disabled without credentials", new KrogerLiveSource().enabled === false);
  process.env.KROGER_CLIENT_ID = saved.id;
  process.env.KROGER_CLIENT_SECRET = saved.secret;
  check("enabled with credentials", src.enabled === true);

  const deals = await src.deals();

  check("returns all three products", deals.length === 3, `got ${deals.length}`);
  const milk = deals.find((d) => d.productName === "Milk 1 Gallon");
  check("promo price used", milk && milk.price === 2.99, JSON.stringify(milk && milk.price));
  check("savings percent computed", milk && milk.savingsPercent === 21, `got ${milk && milk.savingsPercent}`);
  check("live price flagged", milk && milk.estimated === false);
  check(
    "exact product link from productPageURI (tracking query stripped)",
    milk && milk.urlVerified === true && milk.url === "https://www.kroger.com/p/kroger-milk-1-gallon/0001111046396",
    milk && milk.url
  );
  const bread = deals.find((d) => d.productName === "Old Shape Bread");
  check("older price shape still parses", bread && bread.price === 2.49, JSON.stringify(bread && bread.price));
  check("unit price derived from size", milk && milk.unitPrice === "$2.99/gal", `got ${milk && milk.unitPrice}`);
  check("category mapped", milk && milk.category === "Dairy");
  check("brand carried through", milk && milk.brand === "Kroger");
  check("image carried through", milk && milk.imageUrl === "https://img.kroger.com/milk.jpg");

  const eggs = deals.find((d) => d.productName.includes("Eggs"));
  check("regular price fallback when no promo", eggs && eggs.price === 4.29);
  check("zero savings when no promo", eggs && eggs.savingsPercent === 0);
  check("keywords from search term", eggs && Array.isArray(eggs.keywords) && eggs.keywords.length > 0);

  const oauth = calls.find((c) => String(c.url).includes("/oauth2/token"));
  check("OAuth uses client_credentials", oauth && String(oauth.opts.body).includes("grant_type=client_credentials"));
  check(
    "OAuth sends basic auth",
    oauth && String(oauth.opts.headers.Authorization).startsWith("Basic "),
    oauth && oauth.opts.headers.Authorization
  );

  console.log(failures === 0 ? "\nAll Kroger tests passed." : `\n${failures} FAILED`);
  process.exit(failures === 0 ? 0 : 1);
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
