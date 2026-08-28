"use strict";

const crypto = require("crypto");
const fs = require("fs");
const path = require("path");
const { DatabaseSync } = require("node:sqlite");

const ACCESS_TTL_SECONDS = 15 * 60;
const REFRESH_TTL_SECONDS = 30 * 24 * 60 * 60;

function configurationError(message) {
  const error = new Error(message);
  error.status = 503;
  error.expose = true;
  return error;
}

function parseEncryptionKey(raw) {
  if (typeof raw !== "string" || raw.trim().length === 0) {
    throw configurationError("account storage is unavailable: THRIVE_DATA_ENCRYPTION_KEY is not configured");
  }
  const value = raw.trim();
  let key;
  if (/^[0-9a-f]{64}$/i.test(value)) {
    key = Buffer.from(value, "hex");
  } else {
    try {
      key = Buffer.from(value, "base64");
    } catch {
      key = null;
    }
  }
  if (!key || key.length !== 32) {
    throw configurationError("THRIVE_DATA_ENCRYPTION_KEY must be exactly 32 bytes (base64 or 64 hex characters)");
  }
  return key;
}

function tokenHash(token) {
  return crypto.createHash("sha256").update(token, "utf8").digest("hex");
}

function nowSeconds() {
  return Math.floor(Date.now() / 1000);
}

class AccountStore {
  constructor({ databasePath, encryptionKey }) {
    this.key = parseEncryptionKey(encryptionKey);
    this.databasePath = databasePath;
    fs.mkdirSync(path.dirname(databasePath), { recursive: true, mode: 0o700 });
    this.db = new DatabaseSync(databasePath);
    this.db.exec(`
      PRAGMA journal_mode = WAL;
      PRAGMA foreign_keys = ON;
      PRAGMA busy_timeout = 5000;
      PRAGMA secure_delete = ON;
      CREATE TABLE IF NOT EXISTS accounts (
        id TEXT PRIMARY KEY,
        google_sub_hash TEXT NOT NULL UNIQUE,
        profile_nonce BLOB NOT NULL,
        profile_tag BLOB NOT NULL,
        profile_ciphertext BLOB NOT NULL,
        created_at INTEGER NOT NULL,
        updated_at INTEGER NOT NULL
      );
      CREATE TABLE IF NOT EXISTS account_state (
        account_id TEXT PRIMARY KEY REFERENCES accounts(id) ON DELETE CASCADE,
        revision TEXT NOT NULL,
        state_nonce BLOB NOT NULL,
        state_tag BLOB NOT NULL,
        state_ciphertext BLOB NOT NULL,
        updated_at INTEGER NOT NULL
      );
      CREATE TABLE IF NOT EXISTS sessions (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        account_id TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
        family_id TEXT NOT NULL,
        kind TEXT NOT NULL CHECK(kind IN ('access', 'refresh')),
        token_hash TEXT NOT NULL UNIQUE,
        expires_at INTEGER NOT NULL,
        created_at INTEGER NOT NULL,
        revoked_at INTEGER
      );
      CREATE INDEX IF NOT EXISTS idx_sessions_account ON sessions(account_id);
      CREATE INDEX IF NOT EXISTS idx_sessions_expiry ON sessions(expires_at);
    `);
    try {
      fs.chmodSync(databasePath, 0o600);
    } catch {
      // Windows ACLs and some container mounts do not implement POSIX modes.
    }
  }

  encryptJson(value, purpose) {
    const nonce = crypto.randomBytes(12);
    const cipher = crypto.createCipheriv("aes-256-gcm", this.key, nonce);
    cipher.setAAD(Buffer.from(`thrive:${purpose}:v1`, "utf8"));
    const ciphertext = Buffer.concat([cipher.update(JSON.stringify(value), "utf8"), cipher.final()]);
    return { nonce, tag: cipher.getAuthTag(), ciphertext };
  }

  decryptJson(row, prefix, purpose) {
    const nonce = row[`${prefix}_nonce`];
    const tag = row[`${prefix}_tag`];
    const ciphertext = row[`${prefix}_ciphertext`];
    const decipher = crypto.createDecipheriv("aes-256-gcm", this.key, nonce);
    decipher.setAAD(Buffer.from(`thrive:${purpose}:v1`, "utf8"));
    decipher.setAuthTag(tag);
    return JSON.parse(Buffer.concat([decipher.update(ciphertext), decipher.final()]).toString("utf8"));
  }

  upsertGoogleAccount({ sub, name, email, picture }) {
    const subHash = tokenHash(`thrive:google:${sub}`);
    const existing = this.db.prepare("SELECT id FROM accounts WHERE google_sub_hash = ?").get(subHash);
    const accountId = existing ? existing.id : `g_${crypto.randomBytes(16).toString("hex")}`;
    const profile = {
      sub,
      name: typeof name === "string" ? name.slice(0, 160) : null,
      email: typeof email === "string" ? email.slice(0, 320) : null,
      picture: typeof picture === "string" ? picture.slice(0, 2048) : null,
    };
    const encrypted = this.encryptJson(profile, `profile:${accountId}`);
    const timestamp = nowSeconds();
    if (existing) {
      this.db.prepare(`
        UPDATE accounts SET profile_nonce = ?, profile_tag = ?, profile_ciphertext = ?, updated_at = ?
        WHERE id = ?
      `).run(encrypted.nonce, encrypted.tag, encrypted.ciphertext, timestamp, accountId);
    } else {
      this.db.prepare(`
        INSERT INTO accounts
          (id, google_sub_hash, profile_nonce, profile_tag, profile_ciphertext, created_at, updated_at)
        VALUES (?, ?, ?, ?, ?, ?, ?)
      `).run(accountId, subHash, encrypted.nonce, encrypted.tag, encrypted.ciphertext, timestamp, timestamp);
    }
    return { accountId, profile };
  }

  issueSession(accountId, familyId = crypto.randomBytes(16).toString("hex")) {
    const accessToken = crypto.randomBytes(32).toString("base64url");
    const refreshToken = crypto.randomBytes(48).toString("base64url");
    const createdAt = nowSeconds();
    this.db.prepare("DELETE FROM sessions WHERE expires_at <= ? OR (revoked_at IS NOT NULL AND revoked_at <= ?)")
      .run(createdAt - 24 * 60 * 60, createdAt - 7 * 24 * 60 * 60);
    const insert = this.db.prepare(`
      INSERT INTO sessions (account_id, family_id, kind, token_hash, expires_at, created_at)
      VALUES (?, ?, ?, ?, ?, ?)
    `);
    this.db.exec("BEGIN IMMEDIATE");
    try {
      insert.run(accountId, familyId, "access", tokenHash(accessToken), createdAt + ACCESS_TTL_SECONDS, createdAt);
      insert.run(accountId, familyId, "refresh", tokenHash(refreshToken), createdAt + REFRESH_TTL_SECONDS, createdAt);
      this.db.exec("COMMIT");
    } catch (error) {
      this.db.exec("ROLLBACK");
      throw error;
    }
    return {
      accessToken,
      refreshToken,
      tokenType: "Bearer",
      expiresIn: ACCESS_TTL_SECONDS,
      accessTokenExpiresAt: (createdAt + ACCESS_TTL_SECONDS) * 1000,
      refreshExpiresIn: REFRESH_TTL_SECONDS,
    };
  }

  createGoogleSession(profile) {
    const account = this.upsertGoogleAccount(profile);
    return { ...account, tokens: this.issueSession(account.accountId) };
  }

  accountForAccessToken(token) {
    if (typeof token !== "string" || token.length < 32 || token.length > 256) return null;
    const timestamp = nowSeconds();
    const row = this.db.prepare(`
      SELECT a.id, a.profile_nonce, a.profile_tag, a.profile_ciphertext
      FROM sessions s JOIN accounts a ON a.id = s.account_id
      WHERE s.token_hash = ? AND s.kind = 'access' AND s.revoked_at IS NULL AND s.expires_at > ?
    `).get(tokenHash(token), timestamp);
    if (!row) return null;
    return { accountId: row.id, profile: this.decryptJson(row, "profile", `profile:${row.id}`) };
  }

  rotateRefreshToken(token) {
    if (typeof token !== "string" || token.length < 32 || token.length > 256) return null;
    const timestamp = nowSeconds();
    const row = this.db.prepare(`
      SELECT id, account_id, family_id, expires_at, revoked_at FROM sessions
      WHERE token_hash = ? AND kind = 'refresh'
    `).get(tokenHash(token));
    if (!row) return null;
    if (row.revoked_at !== null || row.expires_at <= timestamp) {
      // A rotated refresh token being replayed may indicate theft. Revoke the
      // full family, including any tokens created during the valid rotation.
      this.db.prepare("UPDATE sessions SET revoked_at = ? WHERE family_id = ? AND revoked_at IS NULL").run(timestamp, row.family_id);
      return null;
    }
    this.db.prepare("UPDATE sessions SET revoked_at = ? WHERE id = ?").run(timestamp, row.id);
    this.db.prepare("UPDATE sessions SET revoked_at = ? WHERE family_id = ? AND kind = 'access' AND revoked_at IS NULL").run(timestamp, row.family_id);
    return { accountId: row.account_id, tokens: this.issueSession(row.account_id, row.family_id) };
  }

  revokeSession({ accessToken, refreshToken }) {
    const timestamp = nowSeconds();
    if (typeof accessToken === "string" && accessToken.length >= 32) {
      const row = this.db.prepare("SELECT family_id FROM sessions WHERE token_hash = ?").get(tokenHash(accessToken));
      if (row) this.db.prepare("UPDATE sessions SET revoked_at = ? WHERE family_id = ? AND revoked_at IS NULL").run(timestamp, row.family_id);
    }
    if (typeof refreshToken === "string" && refreshToken.length >= 32) {
      const row = this.db.prepare("SELECT family_id FROM sessions WHERE token_hash = ?").get(tokenHash(refreshToken));
      if (row) this.db.prepare("UPDATE sessions SET revoked_at = ? WHERE family_id = ? AND revoked_at IS NULL").run(timestamp, row.family_id);
    }
  }

  readState(accountId) {
    const row = this.db.prepare(`
      SELECT revision, state_nonce, state_tag, state_ciphertext, updated_at
      FROM account_state WHERE account_id = ?
    `).get(accountId);
    if (!row) return { payload: null, revision: null };
    return {
      payload: this.decryptJson(row, "state", `state:${accountId}`),
      revision: row.revision,
      updatedAt: new Date(row.updated_at * 1000).toISOString(),
    };
  }

  writeState(accountId, payload, revision) {
    const encrypted = this.encryptJson(payload, `state:${accountId}`);
    const timestamp = nowSeconds();
    this.db.prepare(`
      INSERT INTO account_state
        (account_id, revision, state_nonce, state_tag, state_ciphertext, updated_at)
      VALUES (?, ?, ?, ?, ?, ?)
      ON CONFLICT(account_id) DO UPDATE SET
        revision = excluded.revision,
        state_nonce = excluded.state_nonce,
        state_tag = excluded.state_tag,
        state_ciphertext = excluded.state_ciphertext,
        updated_at = excluded.updated_at
    `).run(accountId, revision, encrypted.nonce, encrypted.tag, encrypted.ciphertext, timestamp);
    return new Date(timestamp * 1000).toISOString();
  }

  /** Permanently removes the encrypted profile, state, and every session. */
  deleteAccount(accountId) {
    const result = this.db.prepare("DELETE FROM accounts WHERE id = ?").run(accountId);
    return result.changes === 1;
  }

  rawDatabaseForTests() {
    return this.db;
  }

  close() {
    this.db.close();
  }
}

module.exports = {
  AccountStore,
  ACCESS_TTL_SECONDS,
  REFRESH_TTL_SECONDS,
  parseEncryptionKey,
};
