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

Release signing needs local credentials — see `tools/release.sh` and `app/build.gradle.kts`. The keystore (`thrive-release.keystore`) is gitignored. Put `THRIVE_KEYSTORE_*` only in the user-level `~/.gradle/gradle.properties` file or environment variables; the tracked project `gradle.properties` contains safe build limits and CI rejects signing fields in it. Generate your own keystore before a public release:

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

See `backend/release-notes.json` for per-version notes. Latest: **1.6.3** — **connected-data honesty and backup repair**: Google sign-in now matches the backend contract, repeated account backups use persisted revisions with conflict-safe merge/retry, expired sessions never appear usable, and connection success is shown only after verification. Bundled savings are explicitly planning estimates with retailer destinations; Open Food Facts is used only for images, never as proof of a store offer. Private preferences are excluded from Android backup, signing secrets stay outside tracked files, and dead sync endpoints are omitted from releases. Earlier: v1.6.2 fixed weekly-plan sheet reachability; v1.6.1 added verified-deal trip totals and package-aware register estimates; v1.6.0 introduced the structured weekly planning engine. See the JSON release notes and GitHub Releases for the complete history.

## License

Demo/educational project. Deals are illustrative demo data — product photos come from Open Food Facts and prices are estimates, both labeled honestly in the app.
