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
node server.js            # http://localhost:4000
```

- `GET /api/v1/sync` — full payload (deals, coupons, recipes, catalog) with ETag caching
- `GET /api/v1/deals|/coupons|/recipes|/catalog` — individual feeds
- `POST /api/v1/deals` — admin override, guarded by `THRIVE_ADMIN_TOKEN` (disabled by default)
- The app points at the server in **Settings → Sync server** (emulator default `http://10.0.2.2:4000`, phone uses your LAN IP)

Drop a release APK at `backend/public/Thrive-release.apk` and restart the backend — the app shows an update card with release notes (`backend/release-notes.json`) only when the served version is newer than the installed one.

## Tests

```bash
./gradlew :app:testDebugUnitTest
```

45 unit tests covering the deal-finder honesty rules (no misleading matches, unit-price comparison, category gating), weekly planner cost math (no double-counting), pantry meal scoring, sync payload decoding, and data quality (no placeholder images, image/link consistency).

## Release history

See `backend/release-notes.json` for per-version notes. Latest: **1.2.5** — honest weekly-plan totals, week planning from an empty pantry, keystore credentials moved out of the build file, admin POST guard.

## License

Demo/educational project. Deals are illustrative demo data — product photos come from Open Food Facts and prices are estimates, both labeled honestly in the app.
