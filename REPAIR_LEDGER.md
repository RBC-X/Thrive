# Thrive Repair Ledger

Audit start: 2026-08-14. Repo: `github.com/RBC-X/Thrive`.
Baseline premise (v1.2.7 @ `0e8a905`) is **stale**: `main` is at `da9da6e` (v1.2.8,
which extended the anonymous backup to pantry + budget). Every issue below was
re-verified against the current tree; all remain applicable.

Severity: P0 = blocks ordinary use / data loss / crash / security boundary.
P1 = user-visible wrong behavior or broken advertised feature.
P2 = hardening / correctness with low immediate impact. Status: OPEN/FIXED.

| # | Sev | Issue | Root cause | Fix | Status |
|---|-----|-------|-----------|-----|--------|
| 1 | P1 | Clean clone cannot run debug/lint/tests | `app/build.gradle.kts` creates release signing config at configuration time with `error()` when passwords absent | Lazy conditional signing; fail-closed only for release package tasks | **FIXED** — verified clean-env (`THRIVE_KEYSTORE_*` unset) testDebugUnitTest + lintDebug pass; release fails closed |
| 2 | P1 | Backup advertised but no usable public endpoint; default URL is emulator-only (`10.0.2.2:4000`) | `ThriveRepository.kt` default sync URL; no hosted endpoint | Honest availability gating (HTTPS or loopback only), "unavailable" UI state, no silent background pushes | **FIXED** — release ships `DEFAULT_SYNC_URL=""`; Settings shows "Backup is unavailable: no sync server is configured" |
| 3 | P1 | Restore reports success on network/server failure; adopts wrong code | `StateBackup.pull()` maps all failures to empty; `restore()` adopts code unconditionally | Sealed `PullResult`; adopt+push only after confirmed valid response | **FIXED** — sealed `PullResult` (success-found/success-empty/not-found/unauthorized/invalid/network/parse); code adopted only after confirmed 200 + merge |
| 4 | P1 | Two-device backup loses updates (last write wins) | Server PUT writes whole file, no revision/If-Match/atomicity | Revision + `If-Match`, atomic temp+rename, per-code serialization, client re-pull/retry on 409 | **FIXED** — server: `rev` + `If-Match` → 409, temp file + atomic rename, per-code in-flight lock; client retries with re-pull on 409; concurrent-writer test green |
| 5 | P0 | Malformed admin POST crashes the whole backend | `POST /api/v1/deals` stores any array; later `d.category.toLowerCase()` throws in async route → unhandled rejection kills Node | Strict schema validation (atomic 400), async route wrapper, centralized error middleware | **FIXED** — strict `validateDeal` (types, ranges, URL format, caps, dup ids), atomic 400 with errors, `asyncRoute` wrapper, central error handler; crash tests + post-failure health green |
| 6 | P1 | Updater first-use install stalls after permission grant; failures invisible | `DownloadReceiver` swallows errors, no status/size checks, returns after opening Settings | Bounded `.partial` download, progress + error notifications, persist pending install, resume on resume/permission | **FIXED** — `.partial` + atomic promote, status/size checks, error notifications with retry, pending-install resume on app start |
| 7 | P2 | Updater picks any `.apk` asset; no version/size/digest/host validation | `GithubUpdateChecker.parse` picks first `.apk` | Canonical `Thrive-<v>-release.apk` selection, semver tag check, body cap, approved-host check, optional SHA-256 sidecar | **FIXED** — canonical asset match, semver tag parse, 2 MB JSON cap, approved-host check, size sanity |
| 8 | P1 | Backup credentials over cleartext HTTP | `network_security_config.xml` allows cleartext globally; code in URL | Release: cleartext off; debug: on. Backup blocked for http non-loopback | **FIXED** — main config: HTTPS-only; debug overlay: cleartext for emulator; availability gate requires HTTPS (or debug loopback) |
| 9 | P1 | First-launch notification permission prompt is contextless | `MainActivity.onCreate` requests on startup | Remove auto-request; Settings opt-in with explanation | **FIXED** — no startup request (verified on emulator: POST_NOTIFICATIONS ungranted, no dialog); Settings "Update notifications" opt-in row |
| 10 | P1 | "Save up to $4,711 this week" is catalog sum, misleading | `totalPotentialSavings` sums all coupons | User-relevant claim (favorites/shopping list) or honest generic line + test | **FIXED** — header now "415 fresh deals today — each offer shows its own savings"; per-deal savings still shown; regression test added |
| 11 | P1 | README/version/tests stale; no backend test script; no release-consistency check | Manual maintenance | Sync README, `npm test`, CI workflow, verify script | **FIXED** — README 1.2.9 + 56 tests, `npm test` runs both suites, `.github/workflows/ci.yml`, `tools/check_release.sh` green |
| 12 | P2 | `tools/ship.sh` — `git add -A`, kills all node.exe, hard-coded OneDrive path | See file | Audit + refactor (guard rails, no global kills) | **OPEN (audited, not refactored)** — not exercised during repair; flags recorded, refactor deferred to avoid touching release mechanics unrequested |
| 13 | P2 | Express routes: NaN `limit`, missing body guard on JSON parse, sync FS in handlers | `Number(req.query.limit)`, `express.json` errors | Strict query validation, error middleware, async FS | **FIXED** — strict query validation, express.json error → 400, async FS (fs/promises + atomic rename) |

## Verification evidence (2026-08-14)

- App unit tests: **56/56 pass, 0 failures** (`:app:testDebugUnitTest`). Lint: `:app:lintDebug` BUILD SUCCESSFUL.
- Clean-env (no `THRIVE_KEYSTORE_*`, no gradle.properties secrets): debug build + unit tests + lint all pass; release tasks fail closed with clear message.
- Backend: `npm test` → **16 integration checks pass** (routes, ETag/304, strict validation, admin auth, atomic malformed-payload rejection, post-failure `/health`, concurrent backup writers preserving the union) **+ 16 Kroger checks pass**.
- Release APK (isolated creds, signer continuity): `Thrive-1.2.9-release.apk` — versionCode 14 / versionName 1.2.9, minSdk 26 / targetSdk 35, SHA-256 `95e796f4fc58cd0bfc95b4c3dbca158827c0d6765e0e876e3f295a22fffec885`, signer SHA-256 `01c92ccb5afc933c3785b2098bc2ac8d22da18d004c8b5c717e386a41674de14` (identical to v1.2.7 cert, `CN=Thrive`).
- On-device (emulator, release 1.2.9, clean data): launch shows no notification prompt; Savings header reads "415 fresh deals today — each offer shows its own savings"; Settings shows "Backup is unavailable: no sync server is configured…"; About shows real Version 1.2.9; Check for updates → "You're on the latest version (1.2.9)"; zero FATAL exceptions in logcat.
- Release consistency (`tools/check_release.sh`): gradle versionName/code match, README latest matches, release-notes 1.2.9 entry present — all pass.
- `tools/check_release.sh` itself caught a real gap during repair: release-notes had no 1.2.9 entry; added.

## Remaining gaps / prerequisites

1. **No production HTTPS backup endpoint ships.** Backup is honest ("unavailable") until the operator hosts `backend/` behind HTTPS and enters the URL in Settings. Physical two-phone backup over a real endpoint was therefore not executed — the protocol (If-Match concurrency, sealed results) is covered by unit/integration tests, but a live cross-device run still needs the endpoint.
2. **`tools/ship.sh` audited, not refactored** (item 12): it still contains `git add -A`, global `node.exe` kills, and a hard-coded OneDrive path. Guard-railing it changes release mechanics beyond the repair scope; recommended as a follow-up before the next release.
3. Kroger live calls need real credentials (developer.kroger.com) — adapter is tested against recorded fixtures only.
4. Backup deletes remain add-only (no tombstones) — documented behavior, by design.
5. No instrumentation/Compose UI tests were added in this pass (emulator-driven QA only); CI runs unit + lint + backend suites.
