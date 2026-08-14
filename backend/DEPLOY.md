# Deploying Thrive's backend 24/7 (always-on live prices)

The app talks to one small Node server (`backend/`) over HTTPS. Live Kroger
prices and cross-device backup work only while that server is reachable. This
guide makes it run **24/7 independent of your PC** — pick ONE option.

> The app contains **no keys**. The Kroger secret and admin token live in the
> server's environment. Never put them in the app or in a public repo.

---

## Option A — Small VPS (recommended, ~$5/mo, truly always-on)

Any provider works: DigitalOcean, Hetzner, Linode, Vultr, or a Raspberry Pi at
home. Ubuntu 22.04+ is assumed.

**1. Create a droplet/VPS** (smallest plan, ~$5/mo is plenty — this server is
tiny and does a handful of API calls per minute).

**2. From this machine, copy the backend up** (or `git clone` your repo there):

```bash
# from your PC
scp -r backend ubuntu@YOUR_SERVER_IP:~/
```

**3. SSH in and install Node 20+:**

```bash
ssh ubuntu@YOUR_SERVER_IP
curl -fsSL https://deb.nodesource.com/setup_20.x | sudo -E bash -
sudo apt-get install -y nodejs
```

**4. Configure secrets** (never commit these):

```bash
cd ~/backend
cat > .env <<'EOF'
KROGER_CLIENT_ID=your_client_id
KROGER_CLIENT_SECRET=your_client_secret
KROGER_ZIP=45202
THRIVE_ADMIN_TOKEN=long-random-string      # optional, for the admin deals endpoint
PORT=4000
EOF
```

**5. Run it permanently** with a system service (auto-restarts on crash/boot):

```bash
sudo tee /etc/systemd/system/thrive.service > /dev/null <<'EOF'
[Unit]
Description=Thrive sync API
After=network.target

[Service]
WorkingDirectory=/home/ubuntu/backend
ExecStart=/usr/bin/node server.js
Restart=always
RestartSec=3
EnvironmentFile=/home/ubuntu/backend/.env
User=ubuntu

[Install]
WantedBy=multi-user.target
EOF

sudo systemctl daemon-reload
sudo systemctl enable --now thrive
curl http://localhost:4000/api/v1/health   # {"ok":true,...}
```

**6. Put it behind HTTPS.** Two choices:

- **Cloudflare Tunnel (recommended)** — no open ports, free, stable hostname:
  ```bash
  # on the VPS
  curl -L https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-amd64 -o cloudflared && chmod +x cloudflared
  ./cloudflared tunnel login      # opens a browser; log in to your Cloudflare account
  ./cloudflared tunnel create thrive
  ./cloudflared tunnel route dns thrive yourdomain.com
  # config.yml -> tunnel: <id>, credentials-file, ingress -> http://localhost:4000
  # install it as a service: ./cloudflared service install
  ```
  Then point the app at `https://yourdomain.com`.

- **Caddy** (simplest TLS, auto-HTTPS, needs a domain or it'll give you a
  *.caddy.localhost placeholder):
  ```bash
  sudo apt-get install -y caddy
  sudo tee /etc/caddy/Caddyfile <<'EOF'
  yourdomain.com {
      reverse_proxy 127.0.0.1:4000
  }
  EOF
  sudo systemctl reload caddy
  ```

**7. Publish the URL so the app's one-tap connect finds it:**

```bash
# on your PC (once, and again if the URL ever changes)
echo "https://yourdomain.com" > /tmp/thrive-sync-url.txt
gh release upload v1.2.11 /tmp/thrive-sync-url.txt --clobber -R RBC-X/Thrive
```

That's it — the app's Settings → "Public backup server" Connect button now
points at the always-on server, and live Kroger prices work for anyone, any time.

---

## Option B — Free-tier platforms (no VPS to manage)

- **Railway / Render / Fly.io**: create a free account, point it at the
  `backend/` folder, set the same `.env` variables, deploy. Render's free tier
  sleeps after inactivity (wakes on first request) — fine for backup, slightly
  slow for the first daily load.
- **Cloudflare Workers + D1** would need porting the server; not recommended
  for this codebase yet.

---

## Option C — Keep this PC as host (self-healing)

`tools/thrive_service.sh` restarts the backend + tunnel and re-publishes the
URL whenever anything dies. It survives crashes and reboots:

```bash
# install as a boot task (one time):
schtasks //Create //TN ThriveService //TR "bash C:\Users\bsmit\OneDrive\Documents\Thrive\tools\thrive_service.sh" //SC ONSTART //RU %USERNAME% //RL HIGHEST
```

Caveat: your PC must be on for the app to show live prices. When it's off,
the app honestly shows the bundled feed (v1.2.11+ shows a "bundled feed"
notice) — the app itself never breaks.

---

## Keeping the Kroger credential safe

- The **client secret lives only on the server** (`backend/.env`, gitignored).
- Never embed it in the APK — anything in the app can be extracted by anyone.
- If you ever paste it in a chat/log, rotate it at developer.kroger.com and
  update the server `.env` only.
