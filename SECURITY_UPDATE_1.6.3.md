# Thrive privacy hardening update

This update is published on `main` after auditing public API responses, location handling, Google account backup, and backup files.

## Changes

- Exact user latitude/longitude is no longer returned in `/api/v1/sync` location responses.
- Nearby-store responses contain only store, city, and rounded distance values.
- The backend still uses the submitted location transiently to rank results, but does not echo it back or persist it in the response cache.
- Backup files are encrypted at rest with AES-256-GCM.
- Production backend startup requires `THRIVE_BACKUP_ENCRYPTION_KEY`, a 64-character hexadecimal key.
- Test mode uses a deterministic ephemeral key only for isolated tests.
- Backup writes remain atomic and serialized per account/code.
- Google ID tokens remain in authorization headers and are not stored or logged by the backend.
- Private API keys remain server-side/local-only and are not embedded in the APK.

## Verification

- Backend route/integration tests: **32 passed, 0 failed**.
- Kroger source tests: passed.
- Target source tests: passed.
- Exa source tests: passed.
- Tests verify encrypted backup envelopes, authenticated account access, concurrent writes, malformed input handling, and absence of exact location coordinates from public sync output.

## Required deployment configuration

Set a strong random key on the backend host before starting production:

```text
THRIVE_BACKUP_ENCRYPTION_KEY=<64 hexadecimal characters>
```

Keep it outside GitHub, outside the APK, and outside public logs. Losing this key makes encrypted backups unrecoverable, so store it in the server's secret manager or a protected offline backup.

## Remaining limitation

The Android Gradle checkout is currently on a OneDrive cloud-backed path whose file provider is returning `cloud file provider exited unexpectedly`. The backend changes and tests are verified; the Android test/build gate must be rerun after the checkout provider recovers or from a fully local clone.
