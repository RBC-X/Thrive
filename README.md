# Thrive

An Android app that helps families save money on groceries, cook affordable meals, use what's already in the pantry, and shop on a plan. Built with Jetpack Compose + Kotlin, with a small Node sync backend and fully offline-first AI engines.

## The four parts

| Tab | What it does |
|-----|--------------|
| **Savings** | Daily coupons & deals — store, real product photo, price before/after, expiry, honest link (never fabricated), copy-to-clipboard codes with verification |
| **Recipes** | Affordable family meals (under $10, under 20 min, one-pot, …) with step-by-step guides and brand suggestions |
| **Pantry** | Add what you have → AI suggests meals that use it (bonus for expiring items) → step-by-step plan |
| **Budget** | "How much can you spend?" → build the shopping list → deal finder matches every item with offers, compares unit prices, and groups the trip by store with honest "no verified deal" states |

The weekly meal planner generates 7 dinners under a weekly budget from your pantry and produces one combined shopping list. Cost math is honest — pantry-covered ingredients are never charged twice.

## Building

Requirements: JDK 17+ (built with 21), Android SDK (compileSdk 35, minSdk 26).

```bash
# debug APK
./gradlew :app:assembleDebug          # → app/build/outputs/apk/debug/app-debug.apk

# signed release APK
bash tools/release.sh                 # bumps patch version, builds, stages the APK
```

Release signing needs local credentials — see `tools/release.sh` and `app/build.gradle.kts`. The keystore (`thrive-release.keystore`) and `gradle.properties` (which holds `THRIVE_KEYSTORE_*`) are gitignored and never published. Generate your own keystore before a public release:

```bash
keytool -genkeypair -v -keystore thrive-release.keystore -alias thrive -keyalg RSA -keysize 2048 -validity 10000
```

## Backend (optional live sync)

Thrive works fully offline with its bundled feed. For live sync + the in-app update card:

```bash
cd backend
npm install
npm test                  # backend unit/integration suite
node server.js            # http://localhost:4000
```

- `GET /api/v1/sync` — full payload (deals, coupons, recipes, catalog) with ETag caching
- `GET /api/v1/deals|/coupons|/recipes|/catalog` — individual feeds
- `POST /api/v1/deals` — admin override, guarded by `THRIVE_ADMIN_TOKEN` (disabled by default), strict schema validation
- `GET/PUT /api/v1/backup/:code` — anonymous cross-device state backup with optimistic concurrency (If-Match/ETag) and atomic writes
- `POST /api/v1/auth/google` + `GET/PUT /api/v1/account/backup` — **Google Sign-In backup**: the app signs in with Google, the backend verifies the ID token (Google's public tokeninfo endpoint — no secret on the server) and stores favorites/pantry/budget under a stable key derived from the account, so signing into the same Google account on any device brings everything with it. Backed by the same atomic + optimistic-concurrency machinery as code backups.

**Google Sign-In setup (one-time):** in Google Cloud Console create an OAuth **Web application** client, copy its Client ID, and either add `GOOGLE_CLIENT_ID=...` to `local.properties` (app builds) or set `THRIVE_GOOGLE_CLIENT_ID` (app builds) and `THRIVE_GOOGLE_CLIENT_ID` on the backend for audience enforcement. Without a client ID the app hides the Google card and keeps working with code backups; with one, Settings shows **Sign in with Google** and the legacy code section stays for migration. To configure Google Sign-In on Android you must also register the app's SHA-1 signing fingerprint in the same OAuth client.

**Security policy:** the release build ships with **no default sync server** and backup/update traffic is restricted to **HTTPS** (cleartext is a debug-only developer convenience). A phone user must configure a real public HTTPS endpoint (e.g. a cloudflare tunnel) in **Settings → Sync server**; until one is set, the app honestly reports backup as unavailable. The in-app update channel is fully independent — it reads GitHub releases over HTTPS and needs no server at all.

Drop a release APK at `backend/public/Thrive-release.apk` and restart the backend — the app shows an update card with release notes (`backend/release-notes.json`) only when the served version is newer than the installed one.

## Live retailer prices (Kroger) — one manual step

The backend ships a real Kroger adapter (`KrogerLiveSource`) that does the full
OAuth2 client-credentials flow and returns **live prices** with exact product
links. It activates automatically once credentials exist — with no credentials
it stays disabled and the curated feed keeps serving, so the app never breaks.

1. Sign up **free** at https://developer.kroger.com → *Register* → *Create an app*.
2. Put your Client ID and Client Secret in `backend/.env` (gitignored):
   ```
   KROGER_CLIENT_ID=your_client_id
   KROGER_CLIENT_SECRET=your_client_secret
   KROGER_ZIP=45202        # optional, default 45202
   KROGER_TERMS=eggs milk  # optional, default terms
   ```
3. Restart the backend (`cd backend && npm start`).
4. Verify live prices with `node test_kroger_live.js` — it reports how many
   deals came back live-priced (not estimated) with their real links.

`backend/.env` is loaded by `backend/dotenv.js` for both the server and
standalone scripts; real environment variables always win over the file.

## Tests

```bash
./gradlew :app:testDebugUnitTest   # app unit tests (no signing secrets required)
./gradlew :app:lintDebug           # Android lint
cd backend && npm test             # backend routes, validation, concurrency, crash-resistance
```

Unit tests cover the deal-finder honesty rules (no misleading matches, unit-price comparison, category gating), weekly planner cost math (no double-counting), pantry meal scoring, sync payload decoding, data quality (no placeholder images, image/link consistency), the anonymous-backup merge rules, and the GitHub updater (canonical asset selection, clean semver, host allow-list). CI runs all of these from a clean clone with no signing secrets (`THRIVE_KEYSTORE_*` unset) — debug build, lint, and tests never require the production keystore; release signing fails closed when credentials are missing.

## Release history

See `backend/release-notes.json` for per-version notes. Latest: **1.4.3** — **45 retailers, only real deals, real generative AI recipes**: the catalog grew to 45 retailers with regional grocery chains added (Publix, H-E-B, Safeway, Albertsons, Wegmans, Food Lion, Meijer, Giant, Stop & Shop, Winn-Dixie) so deals come from stores near you; every offer in Savings is genuinely on sale or carries a coupon (products merely on file are never shown as deals); recipe AI is now genuinely generative when you add an AI key in Settings — a real LLM reads your exact pantry and writes a fresh dish for it, with the 144-blueprint on-device engine as the offline fallback; and recipe photos were audited one by one (the unrelated image on Tuna Melts replaced with a real tuna melt photo, all 31 recipes show their actual dish). Then v1.4.2 — **the on-device AI is a real chef now**: 144 recipe blueprints (12 cooking methods × 12 flavor directions), token-based ingredient matching so "boneless skinless chicken breast" really uses your chicken breast, every pantry item woven into the dish (with a second vegetable when you own one), a real food photo on every generated recipe, and "Try another" keeps rolling genuinely new dishes. Recipe photos are fixed app-wide — the 7 recipes that had none got real photos and image loading is browser-grade, so every dish shows its actual photo instead of a gradient tile. And the full coupon catalog is visible: all 4,235 offers with direct links (exact product pages marked verified, retailer search links labeled honestly). Then v1.4.1 — **stability repair pass**: fixed a crash when the deal feed has only one or two offers (the daily pick now selects safely on any feed size), fixed cold-start stale data (synced deals + ETag are cached atomically, so a fast `304` after restart shows your last real sync instead of bundled data, with an honest fallback if the cache is corrupt), locale-aware prices and distances, update notifications routed through a proper broadcast receiver that opens the update dialog, one-shot permission-aware location lookup that never tracks you, every icon button enlarged to a 48dp touch target, the pantry add-item sheet no longer clips at 2× font scale, honest "not available" states for missing deals/recipes, memoized sorting for the 4,000+ coupon catalog, and 4 instrumented UI tests + 23 new unit tests (127 unit + 24 backend green). Then v1.4.0 — **the pantry AI really cooks with what you have**: it matches every ingredient in your pantry, rotates through multiple proteins/veggies/sauces across rolls (and uses a second vegetable when you own one), and "Try another" now walks 8 cooking methods × 8 flavor directions (64 blueprints) plus item rotation — genuinely new dishes every time instead of the same one renamed 4 ways. The "Uses:" list is strictly what's already in your pantry, never a sauce the recipe merely suggests. Then the v1.3.9 **only real, buyable deals are available**: every deal shown in Savings has a verified direct link to the exact product page on the store's own site, and offers without a verified product link are honestly hidden (with a count of how many were hidden). The live feed grew from 63 to **thousands of real Kroger products** — current prices from the Kroger API, real product photos, real brands, and direct `kroger.com/p/...` product-page links — and live items with no running promo say "Live price" instead of inventing a discount. Then the v1.3.8 **images fixed across the app**: every one of the 35 store logos is bundled inside the APK so the logo on every deal card shows instantly and works offline (plus 12 chains — Kroger, Chipotle, Taco Bell, Home Depot, Newegg — gained real logos that previously had none), SVG logos now decode properly, and any image that can't load falls back to the store logo or a clean category tile — never a blank box. Then the v1.3.7 **smarter Savings**: search now matches products, stores, categories, *and* brands and ranks results by savings; a new **Stores** tab groups every deal by store with nearest stores first when you share your location (expand any store to see its offers); the Deal of the Day is now the genuinely strongest offer and a fresh **New this week** shelf highlights new arrivals. Plus the v1.3.6 **coupon catalog grew from 415 to 4,235 offers — no cap**. Thousands of coupons across all 8 categories and 35 retailers (1,215 grocery, 538 tech, 514 essentials, 470 home, 412 beauty, 395 dining, 360 health, 332 travel), each with real store logos as the image fallback, honest estimated prices and store links, and the daily "new today" rotation — search, filters, and the savings header all work across the full catalog. Plus the v1.3.5 smarter recipe AI (accept / try-another / one-tap shopping adds), the v1.3.4 direct product links, and the v1.3.3 real store logos on every deal, recipe food photos, and on-device recipe AI. Also ships the v1.3.2 deal reminders, v1.3.1 nearby deals (opt-in approximate location ranks deals by distance to your nearest store and makes live Kroger prices come from the store nearest to you), the v1.3.0 real product photos on coupons, the visible version badge, the v1.2.11 honest feed labeling ("Live" only when deals really came from the server), the self-healing backend+tunnel watchdog, and an always-on deploy guide (`backend/DEPLOY.md`) with Docker. Run `bash tools/check_release.sh` before shipping to verify README, Gradle metadata, release notes, tag, and APK agree.

## License

Demo/educational project. Deals are illustrative demo data — product photos come from Open Food Facts and prices are estimates, both labeled honestly in the app.
