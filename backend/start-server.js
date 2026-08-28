#!/usr/bin/env node
// Starts the Thrive backend as a detached process writing to a log file.
const { spawn } = require("child_process");
const path = require("path");

const server = spawn(process.execPath, [path.join(__dirname, "server.js")], {
  detached: true,
  stdio: ["ignore", "pipe", "pipe"],
  cwd: __dirname,
});

const fs = require("fs");
const log = fs.createWriteStream(path.join(__dirname, "thrive-backend.log"), { flags: "a" });
server.stdout.pipe(log);
server.stderr.pipe(log);
server.unref();

console.log(`Backend started (PID ${server.pid})`);
