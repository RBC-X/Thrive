"use strict";
/**
 * Minimal .env loader (no dependency): reads backend/.env if present so users
 * can drop KROGER_CLIENT_ID / KROGER_CLIENT_SECRET (and friends) into a file
 * without touching the environment. Real env vars always win.
 *
 * Required by server.js and src/sources.js so both the live server and any
 * standalone script/tool see the same configuration.
 */
(function loadDotEnv() {
  try {
    const p = require("path").join(__dirname, ".env");
    const text = require("fs").readFileSync(p, "utf-8");
    for (const line of text.split(/\r?\n/)) {
      const m = line.match(/^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)\s*$/);
      if (!m) continue;
      const [, key, raw] = m;
      if (process.env[key] === undefined) process.env[key] = raw;
    }
  } catch (_) {
    /* no .env file — fine */
  }
})();
