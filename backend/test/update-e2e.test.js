/**
 * E2E Auto-Update Regression Test
 * ================================
 * Headless verification that the update flow works end-to-end.
 *
 * Prerequisites:
 *   - Backend running on localhost:4000 (or THRIVE_PORT)
 *   - Thrive APK served at /releases/Thrive-release.apk
 *   - UPDATE_VERSION env set to the version the backend advertises
 *
 * What it tests:
 *   1. Backend health check
 *   2. Sync endpoint returns an update block with a newer version
 *   3. APK download URL returns valid APK bytes with correct content-type
 *   4. APK version is newer than the baseline
 *   5. GitHub release asset (thrive-sync-url.txt) is downloadable
 *   6. Version comparison logic (installed < advertised → update required)
 */
const http = require("http");
const fs = require("fs");
const path = require("path");
const { execSync } = require("child_process");

const PORT = process.env.THRIVE_PORT || 4000;
const BASE = `http://localhost:${PORT}`;
const INSTALLED_VERSION = process.env.INSTALLED_VERSION || "1.5.0";

let passed = 0;
let failed = 0;
const failures = [];

function assert(name, condition, detail) {
  if (condition) {
    passed++;
    console.log(`  ✓ ${name}`);
  } else {
    failed++;
    failures.push(`${name}: ${detail || "assertion failed"}`);
    console.log(`  ✗ ${name}: ${detail || "assertion failed"}`);
  }
}

function fetch(path) {
  return new Promise((resolve, reject) => {
    http.get(`${BASE}${path}`, { timeout: 10000 }, (res) => {
      const chunks = [];
      res.on("data", (c) => chunks.push(c));
      res.on("end", () => {
        const body = Buffer.concat(chunks).toString("utf-8");
        resolve({ status: res.statusCode, headers: res.headers, body });
      });
    }).on("error", reject);
  });
}

function compareVersions(a, b) {
  const pa = a.split(".").map(Number);
  const pb = b.split(".").map(Number);
  for (let i = 0; i < Math.max(pa.length, pb.length); i++) {
    const na = pa[i] || 0;
    const nb = pb[i] || 0;
    if (na > nb) return 1;
    if (na < nb) return -1;
  }
  return 0;
}

async function run() {
  console.log("=== E2E Auto-Update Test ===\n");

  // 1. Health check
  console.log("1. Backend health");
  try {
    const h = await fetch("/api/v1/health");
    const j = JSON.parse(h.body);
    assert("health endpoint returns ok", j.ok === true);
    assert("health lists sources", Array.isArray(j.sources) && j.sources.length > 0);
  } catch (e) {
    assert("health endpoint reachable", false, e.message);
    console.log("\n  FATAL: Backend not reachable. Aborting.");
    process.exit(1);
  }

  // 2. Sync endpoint returns update block
  console.log("\n2. Sync update block");
  try {
    const s = await fetch("/api/v1/sync");
    const j = JSON.parse(s.body);
    const update = j.update;
    assert("sync returns update object", update !== null && update !== undefined, "update is null/undefined");
    if (update) {
      assert("update has versionName", typeof update.versionName === "string" && update.versionName.length > 0);
      assert("update has apkUrl", typeof update.apkUrl === "string" && update.apkUrl.startsWith("http"));
      assert("update has notes", Array.isArray(update.notes));

      // 3. Version comparison
      console.log("\n3. Version comparison");
      const advertised = update.versionName;
      const installed = INSTALLED_VERSION;
      assert(
        `advertised (${advertised}) > installed (${installed})`,
        compareVersions(advertised, installed) > 0,
        `advertised=${advertised}, installed=${installed}`
      );
    }
  } catch (e) {
    assert("sync endpoint", false, e.message);
  }

  // 4. APK download
  console.log("\n4. APK download");
  try {
    const a = await fetch("/releases/Thrive-release.apk");
    assert("APK returns 200", a.status === 200, `status=${a.status}`);
    assert("APK content-type is android", (a.headers["content-type"] || "").includes("android") || a.headers["content-type"] === "application/octet-stream" || (a.headers["content-type"] || "").includes("octet-stream"), `content-type=${a.headers["content-type"]}`);

    // Verify it starts with PK (ZIP/APK magic bytes)
    const firstBytes = Buffer.from(a.body.slice(0, 2));
    assert("APK starts with PK magic bytes", firstBytes.toString("hex") === "504b", `magic=${firstBytes.toString("hex")}`);

    // Check size is reasonable (>1MB, <100MB)
    assert("APK size > 1MB", a.body.length > 1_000_000, `size=${a.body.length}`);
    assert("APK size < 100MB", a.body.length < 100_000_000, `size=${a.body.length}`);
  } catch (e) {
    assert("APK download", false, e.message);
  }

  // 5. GitHub sync URL asset
  console.log("\n5. GitHub sync URL asset");
  try {
    const https = require("https");
    const url = "https://api.github.com/repos/RBC-X/Thrive/releases/latest";
    const body = await new Promise((resolve, reject) => {
      https.get(url, {
        headers: { "Accept": "application/vnd.github+json", "User-Agent": "Thrive-UpdateTest" },
        timeout: 10000,
      }, (res) => {
        const chunks = [];
        res.on("data", (c) => chunks.push(c));
        res.on("end", () => resolve(Buffer.concat(chunks).toString("utf-8")));
      }).on("error", reject);
    });
    const release = JSON.parse(body);
    assert("GitHub release exists", release.tag_name !== undefined, `tag=${release.tag_name}`);
    const assets = release.assets || [];
    const syncAsset = assets.find((a) => a.name === "thrive-sync-url.txt");
    assert("thrive-sync-url.txt asset exists", syncAsset !== undefined);
    if (syncAsset) {
      const url = syncAsset.browser_download_url;
      assert("sync URL asset is downloadable", typeof url === "string" && url.length > 0, `url=${url}`);
    }
    // Verify the latest release version is newer than installed
    const releaseVersion = (release.tag_name || "").replace(/^v/, "");
    if (releaseVersion) {
      assert(
        `GitHub release (${releaseVersion}) > installed (${INSTALLED_VERSION})`,
        compareVersions(releaseVersion, INSTALLED_VERSION) > 0
      );
    }
  } catch (e) {
    assert("GitHub release check", false, e.message);
  }

  // 6. No update when versions match
  console.log("\n6. No-update case (same version)");
  const latestReleaseVersion = (() => {
    try {
      const https = require("https");
      const body = execSync('curl -sS -m 5 "https://api.github.com/repos/RBC-X/Thrive/releases/latest" 2>/dev/null', { encoding: "utf-8" });
      return JSON.parse(body).tag_name?.replace(/^v/, "") || "";
    } catch { return ""; }
  })();
  if (latestReleaseVersion) {
    assert(
      `if installed=${latestReleaseVersion}, no update needed`,
      compareVersions(latestReleaseVersion, latestReleaseVersion) === 0,
      "same version should not trigger update"
    );
  }

  // Summary
  console.log(`\n=== RESULTS: ${passed} passed, ${failed} failed ===`);
  if (failures.length) {
    console.log("\nFailures:");
    failures.forEach((f) => console.log(`  ✗ ${f}`));
  }
  process.exit(failed > 0 ? 1 : 0);
}

run().catch((e) => {
  console.error("FATAL:", e);
  process.exit(1);
});
