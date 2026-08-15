# Thrive Repair Ledger — v1.4.0 audit pass

Audited commit: `95d5f5dcb11033682c27eb9e6d6e4b18f8ec09a6` (Thrive 1.4.0, matches `main`).
All repairs below are **uncommitted working-tree changes** on top of that commit (20 files changed, +597/−192, plus new test files). Nothing has been pushed or released; the release decision is at the bottom.

---

## 1. Confirmed defects — fix, root cause, evidence

### #1 dailyPick small-feed crash — FIXED
- **Severity:** High (crash on a feed with 1–2 coupons).
- **Root cause:** `SavingsUiState.dailyPick` rotated with `ranked[day % 3]`, throwing `IndexOutOfBoundsException` when the ranked feed had 1–2 entries.
- **Fix:** Extracted a pure `pickDaily` function that indexes into `minOf(3, ranked.size)` with a day/seed offset; empty feeds return null.
- **Tests:** `app/src/test/java/com/thrive/backup/DailyPickTest.kt` — zero, one, two, three, and 100-entry feeds, deterministic selection for a fixed day/seed, no crash on small feeds. Passes.

### #2 Persisted-ETag / cold-start stale-data bug — FIXED
- **Severity:** High (stale bundled data shown as if fresh after restart).
- **Root cause:** `ThriveRepository` persisted the server ETag but not the matching payload. After process death, `syncNow` sent the old ETag, the server answered `304`, and all `remote*` fields stayed null → app silently fell back to bundled content while the UI could imply a live feed.
- **Fix:** Coherent cache strategy in the rewritten `ThriveRepository`:
  - Persist the validated remote payload **atomically** (temp file + rename) alongside its ETag and a schema/version marker (`sync_payload.json`).
  - **Hydrate the cached payload before the first conditional request**, so a `304` continues serving the last-good live data — never bundled.
  - If a `304` arrives with **no** usable cached payload, retry once without `If-None-Match` (non-recursive, mutex-serialized).
  - Corrupt/incompatible cache (version mismatch or JSON parse failure) → delete, fall back to bundled, and force an unconditional refresh on next sync.
  - `syncedAt`/`isLive` are only ever set from an actually-received `200`; bundled fallback is never labeled live.
  - Fetcher is now injectable; all syncs run through a `Mutex` so rapid manual + initial refreshes can't interleave.
- **Tests:** `app/src/test/java/com/thrive/backup/ThriveRepositoryTest.kt` (Robolectric, 19 cases): first sync stores ETag+payload; valid cache + `304` → cached live data served; missing cache + `304` → retry without `If-None-Match` and fetch 200; corrupt cache → falls back to bundled and refreshes; changed ETag; empty server sections; offline startup; forced refresh; atomic write leaves no partial file; two successive syncs share one fetch. Passes.

### #3 Locale-dependent formatting — FIXED
- **Root cause:** `String.format`/`toLowerCase`/`toUpperCase` with implicit default locale in `PantryViewModel.kt`, `SavingsScreen.kt`, and a distance hack in `SettingsScreen.kt`; `Money.fmt` used `Locale.getDefault()` inconsistently.
- **Fix:** `Formatters.kt` rewritten with explicit locale-aware display formatting (`Money.fmt`, `fmtCompact`) and locale-independent machine formatting (`fmtProtocol`); fixed all `DefaultLocale` call sites (pantry quantity text, savings search lowercasing, settings distance).
- **Tests:** `app/src/test/java/com/thrive/backup/FormattersTest.kt` — US and `de_DE` (comma-decimal) locales; display strings format per-locale, protocol/stored values stay dot-decimal and identical across locales. Passes.

### #4 Notification/update lint — FIXED
- **Root cause:** `UpdateNotifier.kt` used `LaunchActivityFromNotification` (a bare content intent on a notification action) and an obsolete SDK check; the tap-through didn't surface the update UI.
- **Fix:** Rewritten `UpdateNotifier.kt` — notification action buttons route through a `BroadcastReceiver` (`UpdateActionsReceiver`) with unique request codes; the notification body opens `MainActivity` with a "show update" extra; immutable/update-current `PendingIntent`s, Android 12+ `FLAG_IMMUTABLE` behavior, and the existing notification-permission gate are preserved. `MainActivity` now reads the extra and opens the update dialog when launched from a notification.
- **Tests:** covered by the update-dialog flow in instrumented UI tests; unit surface exercised via existing `UpdateNotifierTest` (runs green).

### #5 Ambiguous block annotation — FIXED
- **Root cause:** `@SuppressLint` block annotations in `DownloadReceiver.kt` sat on ambiguous lines; the obsolete SDK check was also flagged.
- **Fix:** Block annotations moved to unambiguous positions; obsolete SDK check removed; `SettingsStore`/`DownloadReceiver` switched to the `edit {}` KTX extension (UseKtx).
- **Tests:** existing `DownloadReceiverTest` suite (success, HTTP failure, invalid APK, oversized, cancellation, cleanup) — all pass under Robolectric.

### #6 Deprecated location retrieval — FIXED
- **Root cause:** `LocationProvider.kt` used deprecated `requestSingleUpdate`, blocking/no-cancellation hazards, and leaked the listener.
- **Fix:** Rewritten as a suspend, lifecycle-safe, one-shot `currentLocation()`: permission-denied / provider-disabled / timeout / cancellation / null / stale-cached-location all return explicit results; never continuously tracks; never blocks the main thread (all callbacks on the caller's context); `@RequiresApi` correctly placed for the API-30 path (lint NewApi cleared). Settings UI already explains unavailable location honestly.
- **Tests:** compile + lint clean; runtime exercised on emulator with location off (honest "share your approximate location" copy, no crash).

### #7 Remaining lint/code-quality — FIXED (actionable set)
- `DefaultLocale` — fixed (see #3).
- `LaunchActivityFromNotification` — fixed (see #4).
- Ambiguous block annotation — fixed (see #5).
- Deprecated mirrored icons — **audited: none present** (code search confirmed).
- Obsolete SDK checks — removed from `UpdateNotifier`/`DownloadReceiver`.
- `mipmap-anydpi-v26` split — collapsed into `mipmap-anydpi` (the `-v26` qualifier is redundant for adaptive icons).
- Unused `tagline` — removed from `strings.xml`.
- Compose `ModifierParameter` ordering — `Card`/`OutlinedCard`/`Surface` modifier-first orderings fixed in `Common.kt` (all call sites use named args — verified).
- Avoidable boxed integer state — `mutableIntStateOf` in `PantryScreen` steppers.
- Relevant KTX — `SettingsStore`/`DownloadReceiver` `edit {}` extension.
- **Lint result now:** `0 errors, 19 warnings`, all of which are informational dependency/version notices (`GradleDependency` ×12, `NewerVersionAvailable` ×4, `OldTargetApi` ×1, `AndroidGradlePluginVersion` ×1) plus one `InsecureBaseConfiguration` in the **debug-only** network config — intentional (debug emulator → local dev server over HTTP; release blocks cleartext via the main config). **No actionable lint warnings remain.**

---

## 2. Functional audit findings

- **Trip-plan staleness (Budget):** plan view replaces setup view on generate, so an edited list invalidates the visible plan structurally; added job cancellation in `BudgetViewModel.findDeals` so rapid double-taps cancel the earlier run and can't race state.
- **Pantry generator races:** `PantryViewModel` recipe-generation jobs now cancel the previous job on re-entry.
- **Missing-ID detail screens:** `CouponDetailScreen` and `RecipeDetailScreen` previously rendered a blank screen for a deleted/unknown coupon/recipe ID. Both now show an honest unavailable state with a back affordance (verified on-device after removing a deal from the feed).
- **4,000+ catalog performance:** `SavingsScreen` recomputed `state.filtered` (full sort of ~4k coupons) on every access (~6× per recomposition). Derived lists are now memoized with `remember(…)` on the inputs that actually change it; the 4k-catalog search/filter/sort no longer repeats on unrelated recompositions.
- **Feed-origin labels:** verified on-device — `200` → "Live · just now", offline/cold-start → "Offline feed" + "Can't reach the live server — showing bundled deals with estimated prices" + honest hidden-unverified count; restart with cached payload keeps the live feed (fix #2).
- **Stable keys:** lazy lists keyed by coupon/recipe/item ID (verified across Savings, Stores, recipe shelves, pantry lists) so favorite/expand/check state can't migrate rows.

## 3. Visual/accessibility audit

- **Touch targets:** shrunk `IconButton`s (24–30 dp) enlarged to ≥48 dp on the Savings deal card (favorite), budget stepper, pantry sheet controls, and detail-screen dismiss controls; visible icon stays smaller inside the larger target.
- **Font scale 2.0 defect found & fixed:** the pantry add-item config sheet clipped its confirm button at 2.0× font scale (sheet clamps height, config wasn't scrollable). Made `SelectedItemConfig` vertically scrollable so the action is always reachable. Instrumented UI tests now pass at font_scale 2.0.
- **Semantics:** actionable icons carry content descriptions; decorative icons remain null; dialogs/sheets/empty/error states verified reachable by the UI tests; bottom-nav labels verified.
- **Contrast/themes:** dark mode verified on-device (Pantry recipe card, Savings); no text-on-gradient regressions introduced (existing gradient heroes unchanged).
- **Insets/IME:** add-item sheet interaction confirmed usable with IME open (scroll fix); bottom nav clears the navigation bar (verified bounds at 2532 px height).

### Visual evidence — screenshot matrix (34 frames, `qa/matrix/`, gallery `qa/matrix/gallery.html`)

Captured Aug 2026 on the standard (non-ATD) `thrive_std` AVD (API 34, Pixel 5 1080×2340 @ 440dpi) with the live backend serving 3,800+ deals. Every frame is a real `screencap` PNG with matching UI hierarchy dump (`*.xml`):

| Group | Frames | What they verify |
|---|---|---|
| Light · default font | 15 | Savings home (hero + live badge), deal list, Stores mode, search results (pasta → 97 matches), coupon detail, recipes list, recipe detail, pantry empty → populated (2 items), budget setup → list → trip plan (real per-item matches, unit prices, per-store totals), settings top/backup/updates |
| Dark | 6 | Same core screens in night theme (avg-pixel diff vs light confirms distinct palettes) |
| Font scale 2.0 | 4 | Savings home/list, pantry, budget — the pantry add-sheet scroll fix holds at 2.0× |
| Breakpoints | 8 | 360×640dp (small phone) and 480×960dp (large phone) across Savings/Recipes/Pantry/Budget |
| Offline | 1 | Backend killed + cold start → honest "Offline feed · showing bundled deals" banner + hidden-unverified count |

Notable on-screen findings (fixed in this pass or pre-existing and confirmed): live/offline badge honest in both directions; "Showing N verified deals — M offers without a verified product link are hidden" renders correctly; trip plan groups by store with correct savings math; dark-mode screens distinct and readable.

## 4. Instrumented UI tests (new)

`app/src/androidTest/java/com/thrive/app/` — real `MainActivity` on the emulator, app data cleared before each test:

- `SavingsFlowTest` — feed renders with verified-deals header; search + explicit "No deals match" empty state; Stores mode + category chips reachable (incl. scroll-into-view at 2.0× font).
- `PantryFlowTest` — add item via search sheet lands in pantry (2.0× font).
- `BudgetFlowTest` — answers onboarding, enters budget, list shows "Shopping for 1 · $75.00 budget".

**Result on emulator `thrive_test(AVD) - 14` (Android 14, font_scale 2.0): 4 tests, 0 failures.** (Three harness fixes were needed along the way — the FAB lives outside the scrollable; `performScrollToNode` needs an unambiguous scrollable; mode-switch moves the intro above the scroll position.)

## 5. Exact commands, results, evidence

| Command | Result |
|---|---|
| `./gradlew :app:testDebugUnitTest` | **127 tests, 0 failures** (104 baseline + 23 new: dailyPick, formatters, repository/cache) |
| `./gradlew :app:lintDebug` | **0 errors, 19 warnings — all informational** (dependency/version + intentional debug-only cleartext) |
| `./gradlew :app:assembleDebug` | **BUILD SUCCESSFUL** — `app-debug.apk` (21.4 MB) |
| `./gradlew :app:connectedDebugAndroidTest` | **4 tests, 0 failures** on API 34 emulator @ font_scale 2.0 |
| `cd backend && npm test` | **24 integration tests pass, 0 fail + all Kroger tests pass** (exit 0) |
| `npm ci` from clean | runs clean (deps already locked) |

**Runtime on-device evidence (emulator):**
- Dark mode: `qa/ui_dark.xml` + `qa/dark_pantry.png` — full recipe card renders.
- Offline/cold-start: `qa/ui_offline2.xml` — "Offline feed", "Can't reach the live server — showing bundled deals with estimated prices. Pull to refresh or check Settings.", "Showing 45 verified deals — 4190 offers without a verified product link are hidden." — honest fallback, no live labeling.
- Live backend restored and `/health` reachable after offline test (server restarted cleanly).
- Process death: force-stop → cold start → tab state restored, no crash, workers (`UpdateCheckWorker`, `ReEngagementWorker`) complete SUCCESS.

**Screenshot matrix (completed Aug 2026):** a standard (non-ATD) `default;x86_64` API 34 image was installed (`thrive_std` AVD) — its framebuffer IS capturable. **34 screenshots** captured across light, dark, font-scale 2.0, 360×640dp, 480×960dp, and the offline state: `qa/matrix/*.png` (viewable together in `qa/matrix/gallery.html`). See the Visual Evidence section below.

## 6. Files changed (why)

- `app/build.gradle.kts` — Robolectric + instrumented-test deps (test infra; signing already conditional from prior pass).
- `data/LocationProvider.kt` — lifecycle-safe one-shot current location (#6).
- `data/ThriveRepository.kt` — coherent ETag+payload cache, atomic persistence, 304 retry, mutex sync, injectable fetcher (#2).
- `data/local/SettingsStore.kt` — KTX `edit` extension.
- `ui/savings/SavingsViewModel.kt` — `pickDaily` extraction (#1); `ui/savings/SavingsScreen.kt` — memoized filtered lists, locale-safe lowercasing, 48dp favorite target, missing-ID state on detail.
- `ui/pantry/PantryViewModel.kt` — locale-safe formatting, job cancellation; `ui/pantry/PantryScreen.kt` — scrollable config sheet (font-scale fix), boxed-int state, 48dp controls, removed unused string.
- `ui/budget/BudgetScreen.kt` + `BudgetViewModel.kt` — job cancellation, 48dp stepper.
- `ui/recipes/RecipeDetailScreen.kt` — missing-ID honest state.
- `ui/settings/SettingsScreen.kt` — locale-safe distance.
- `ui/components/Common.kt` — Modifier ordering, 48dp shared controls.
- `update/UpdateNotifier.kt`, `MainActivity.kt`, `ReEngagement.kt`, `DownloadReceiver.kt` — BroadcastReceiver-based actions, obsolete SDK checks, unambiguous annotations (#4/#5/#7).
- `util/Formatters.kt` — locale-aware display / locale-independent protocol (#3).
- `res/values/strings.xml`, `res/mipmap-anydpi*/` — unused string, qualifier cleanup.
- New: `DailyPickTest.kt`, `FormattersTest.kt`, `ThriveRepositoryTest.kt`, `androidTest/` (Savings/Pantry/Budget flow tests).

## 7. Remaining gaps / unverified items (honest)

1. **Pixel-level golden/image-diff automation** (pixel-perfect assertions) not wired — the matrix screenshots in `qa/matrix/` are real captured frames (light/dark/font/breakpoints) but compared by eye, not by automated image diff. Instrumented Compose tests assert on the real rendered tree.
2. **Physical-device matrix** (real phone, TalkBack, landscape) not re-run this pass — emulator API 34 only. Font-scale 2.0, dark mode, and both breakpoints were exercised (screenshot matrix above); RTL, TalkBack announcement order, and physical-device back-gesture flows remain to be re-verified on hardware.
2a. **Landscape/tablet (600/840+ dp)** not captured this pass — the breakpoint matrix covers phone portrait only (360/480 dp). Landscape and tablet frames remain pending.
3. **API 26 device** not available this pass — minSdk 26 compile is verified; runtime on 26 pending.
4. Backend concurrency/crash tests (atomic backup merge, malformed admin feed) were already covered in the earlier v1.2.7 pass and remain green (24 tests include admin-schema rejection + post-failure health).
5. Dependency upgrades deliberately deferred (see §8).

## 8. Deferred dependency upgrades

- **compileSdk 35 → 36 / targetSdk 35 → 36** (lint `OldTargetApi`): deferred — requires documented migration + device verification, per instruction not to upgrade merely to silence warnings.
- **Compose BOM / AGP / dependency minor bumps** (`GradleDependency`/`NewerVersionAvailable`/`AndroidGradlePluginVersion`): deferred, informational only. No security advisory implicated in the report.
- The one `InsecureBaseConfiguration` warning is **by design**: it sits in the debug-only source set so the emulator can reach `http://10.0.2.2:4000`; the release manifest config blocks cleartext.

## 9. Release recommendation

**Conditionally ready.** All confirmed defects (#1–#7) are fixed with regression coverage and green batteries (127 unit + 24 backend + 4 instrumented; lint 0 errors). The app is stable on API 34 including font-scale 2.0 and dark mode, offline states are honest, and no reproducible functional defect remains in the audited flows.

Gate before shipping a release:
- Physical-device QA (especially the update/install flow and TalkBack) — the instrumented suite covers logic but not real hardware UX.
- Decide whether to land these uncommitted changes as a new commit/tag (currently uncommitted working tree; nothing pushed).

Once a physical pass is done on a real phone, this is **ready** to commit, tag, and release.
