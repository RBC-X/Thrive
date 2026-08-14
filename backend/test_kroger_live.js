"use strict";
/**
 * Live Kroger verification — requires real credentials.
 *
 * Usage:
 *   KROGER_CLIENT_ID=xxx KROGER_CLIENT_SECRET=yyy node test_kroger_live.js
 *   # or: put the credentials in backend/.env and run `node test_kroger_live.js`
 *
 * If the backend's .env is present it is loaded here too so `node test_kroger_live.js`
 * works with no environment fiddling. This script performs REAL authenticated
 * calls against developer.kroger.com and reports exactly what the feed would
 * contain. Never run it without credentials — it will tell you they are missing.
 */
const { KrogerLiveSource } = require("./src/sources");

(async () => {
  const src = new KrogerLiveSource();
  if (!src.enabled) {
    console.error(
      "Kroger credentials are not configured.\n" +
        "1. Sign up free at https://developer.kroger.com (Register / Create an app)\n" +
        "2. Note your Client ID and Client Secret\n" +
        "3. Add them to backend/.env:\n" +
        "     KROGER_CLIENT_ID=your_client_id\n" +
        "     KROGER_CLIENT_SECRET=your_client_secret\n" +
        "     KROGER_ZIP=45202        # optional, default 45202\n" +
        "     KROGER_TERMS=eggs milk  # optional, default terms\n" +
        "4. Restart the backend and re-run: node test_kroger_live.js"
    );
    process.exit(2);
  }

  console.log(`Kroger source enabled (zip ${src.zip}). Fetching live deals...`);
  const started = Date.now();
  const deals = await src.fetch();
  const ms = Date.now() - started;
  console.log(`\nFetched ${deals.length} deals in ${ms}ms.`);

  if (deals.length === 0) {
    console.error("No deals returned — check ZIP validity / network.");
    process.exit(1);
  }

  let live = 0;
  for (const d of deals.slice(0, 8)) {
    const price = d.priceAfter != null ? `$${d.priceAfter.toFixed(2)}` : "n/a";
    const was = d.priceBefore != null && d.priceBefore > 0 ? `was $${d.priceBefore.toFixed(2)}` : "";
    const est = d.estimated ? " [ESTIMATED]" : " [LIVE]";
    if (!d.estimated) live++;
    console.log(`  - ${d.store} | ${d.title.slice(0, 48)} | ${price} ${was}${est}`);
    if (d.url) console.log(`      link: ${d.url}`);
  }

  console.log(
    `\n${live}/${deals.length} deals are LIVE-priced (the rest fall back to regular price or are estimated).`
  );
  console.log("Kroger live integration OK.");
})();
