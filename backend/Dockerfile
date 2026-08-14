# Thrive backend — runs anywhere Docker runs (VPS, Railway, Fly.io, Render).
#
# Build:      docker build -t thrive-backend backend/
# Run:        docker run -p 4000:4000 -e KROGER_CLIENT_ID=... -e KROGER_CLIENT_SECRET=... thrive-backend
# Compose:    see backend/docker-compose.yml
#
# The container is stateless: backup data is written to /data (mount a volume
# to persist it across restarts). Secrets come from the environment or a
# mounted .env — never bake them into the image.
FROM node:20-alpine

WORKDIR /app
COPY package.json package-lock.json* ./
RUN npm install --omit=dev

COPY . .

ENV PORT=4000
# Backup data lives here; mount a volume to survive restarts.
ENV THRIVE_BACKUP_DIR=/data/backups
RUN mkdir -p /data/backups

EXPOSE 4000

# Healthcheck so orchestrators can restart a dead server automatically.
HEALTHCHECK --interval=30s --timeout=5s --retries=3 \
  CMD wget -qO- http://127.0.0.1:4000/api/v1/health || exit 1

CMD ["node", "server.js"]
