# Thrive 1.6.3 — Release status

## Published update

- GitHub release: https://github.com/RBC-X/Thrive/releases/tag/v1.6.3
- Main commit: `9df37e94772007a93b5916e3b93d4c715fce2846`
- Previous release commit: `0f06772`
- APK asset: `Thrive-1.6.3-release.apk`
- APK SHA-256: `dc6e6917e9438d3154bce481d1107f7df73343980d499056fc891b7bbbe9eb03`
- Package: `com.thrive.app`
- Version: `1.6.3` / versionCode `41`

## Included features

- Settings grouped into Account, Budget, Appliances, Updates, and About Thrive.
- Google Sign-In account backup plumbing and account-keyed backup endpoints.
- Pantry, budget, favorites, and shopping-list backup support.
- Persisted appliance preferences for air fryer, slow cooker, oven, stovetop, and microwave.
- Planner merges saved appliance preferences with appliances detected from the planning request.
- Deal read tracking so previously seen offers do not continue appearing as new.
- Removed the large live-server warning banner; the compact feed-status chip remains truthful.
- Strict direct-product-link handling: Kroger items without an authoritative product-page URI are not verified.
- On-device AI model download flow remains available without API keys.
- Existing offline-first Savings, Recipes, Pantry, Budget, updater, location, retailer, and planner features preserved.

## Verification

- Android unit tests: 197 passed, 0 failed.
- Backend route/integration tests: 32 passed, 0 failed.
- Kroger source tests: passed.
- Target source tests: passed.
- Exa source tests: passed.
- Debug APK build: passed.
- Release APK build for v1.6.3: passed before the final post-release maintenance change.
- Release signer continuity: passed.
- Signer certificate SHA-256: `01c92ccb5afc933c3785b2098bc2ac8d22da18d004c8b5c717e386a41674de14`.

## Honest limitations

- The final direct-link/read-tracking maintenance changes are pushed to `main`, but a replacement APK could not be rebuilt afterward because the OneDrive cloud file provider failed while Gradle was reading the checkout. The published v1.6.3 APK is the earlier release build, not a rebuilt artifact containing those post-release maintenance changes.
- The previous quick Cloudflare tunnel hostname expired. A permanent named tunnel still requires a domain configured in Cloudflare. Public 24/7 backup hosting is not claimed here.
- Backup account files currently use the existing server storage implementation; encryption at rest has not been independently verified and should be implemented before making a production security claim.
- Higgsfield CLI is installed and authenticated, but the free account has one credit and the selected image-generation job requires the Basic plan. No Higgsfield image was generated or added to the app.
- A complete first-launch onboarding flow for appliance and budget questions is not yet present; those settings are available from Settings and the planner UI.

## Security note

Private retailer, AI, and backend credentials must remain on the server or in local ignored configuration. They must not be embedded in the APK, because APK contents can be extracted by users.
