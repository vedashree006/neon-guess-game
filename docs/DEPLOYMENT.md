# Deployment guide

## 1. What this application needs from a host

| Need | Why |
|---|---|
| A **Java servlet container** (Tomcat 10.1+, Java 17) or Docker | Jakarta Servlet 6 app packaged as a WAR |
| A **persistent disk** for `NEON_DB_DIR` | The H2 file database holds scores, analytics and consent records |
| **HTTPS** in front of it | `Secure` cookies and HSTS only make sense (and only work) over TLS |
| **Exactly one running instance** | Embedded H2 is single-process; visitor locks are per JVM |
| Environment variables / secret store | `ADMIN_USERNAME`, `ADMIN_PASSWORD_HASH`, other settings |

**Hosts that cannot run it:** static hosting (GitHub Pages, Netlify/Vercel static, S3 website, most "free web hosting").
They can serve the HTML but have no servlet engine, no database, no consent API and no analytics. Platforms that give you
an ephemeral filesystem (no volume/disk) can run the app, but the database is lost on every restart or redeploy, so
analytics and the leaderboard would not be persistent. Do not publish it that way.

The repository currently contains no hosting configuration, only a local Cargo/Tomcat run, so this guide
proposes an architecture rather than adapting an existing one.

## 2. Recommended: one small VPS in the EU + Docker Compose + Caddy

A single VPS with a persistent disk satisfies every requirement above, keeps the data in one place (useful for
GDPR questions), and is the simplest thing that is actually persistent. Pick any provider with an EU/German data
centre. I have not verified any provider's current plans or prices; check their documentation.

```bash
# On a fresh Ubuntu/Debian server (as a sudo user)
sudo apt update && sudo apt install -y docker.io docker-compose-v2 git ufw
sudo ufw allow OpenSSH && sudo ufw allow 80 && sudo ufw allow 443 && sudo ufw enable

git clone <your-repo-url> neon-guess-game && cd neon-guess-game
cp .env.example .env
```

Edit `.env`:

```
APP_ENV=production
TRUST_PROXY=true                  # Caddy sits in front and overwrites X-Forwarded-For
DOMAIN=game.example.com           # DNS A/AAAA record must already point at this server
ADMIN_USERNAME=owner
ADMIN_PASSWORD_HASH=pbkdf2_sha256:600000:...   # generate it, see below
```

Generate the hash without installing Java on the server (uses the build image; `-it` gives a terminal so the password is not echoed):

```bash
docker run --rm -it -v "$PWD":/w -w /w maven:3.9-eclipse-temurin-17 \
  sh -c 'mvn -q -DskipTests compile >/dev/null && java -cp target/classes com.vedashree.game.security.PasswordHasher'
```

Start:

```bash
docker compose --profile https up -d --build
docker compose logs -f app          # look for "Database ready" and "admin configured=true"
```

Caddy obtains and renews the TLS certificate automatically. The app listens only on `127.0.0.1:8080` inside the host;
the public sees only ports 80/443.

**Update later:** `git pull && docker compose --profile https up -d --build` (the `neon-data` volume is kept).

## 3. Alternatives

- **Platform-as-a-service with Docker + a persistent volume** (for example Render, Fly.io, Railway):
  deploy the `Dockerfile`, attach a volume mounted at `/data`, set the environment variables in the dashboard,
  and keep the service at one instance. The platform terminates TLS, so set `APP_ENV=production` and `TRUST_PROXY=true`.
  Tomcat listens on **8080**; if the platform injects a different `PORT`, map/route port 8080 instead.
  Confirm in the provider's docs that volumes are available on the plan you choose, and use `/healthz` as the health-check path.
- **Existing Tomcat 10.1 server:** `mvn clean package`, copy `target/neon-guess-game.war` to `webapps/ROOT.war`,
  put the environment variables in `bin/setenv.sh` (`export ADMIN_USERNAME=...`), terminate TLS in nginx/Apache
  and proxy to `127.0.0.1:8080`. Make sure the proxy sets `X-Forwarded-For` (replacing client-supplied values)
  before you enable `TRUST_PROXY=true`.

## 4. HTTPS and secure cookies

- `APP_ENV=production` makes the session, consent and analytics cookies `Secure` and sends
  `Strict-Transport-Security`. Without HTTPS, browsers will not return `Secure` cookies (except on `localhost`),
  and the game session breaks. That is intentional: never run production over plain HTTP.
- All cookies are `SameSite=Strict`; session, consent and visitor cookies are `HttpOnly`.
- After the first successful HTTPS deployment you may submit the domain to an HSTS preload list, but only
  if every subdomain is HTTPS too. The app sends `max-age=31536000` without `includeSubDomains` or `preload`.

## 5. Secrets

- Put secrets only in `.env` on the server (permissions `chmod 600 .env`) or the platform's secret store.
  `.env` is in `.gitignore` and `.dockerignore`; `.env.example` contains no real values.
- Only the PBKDF2 **hash** of the admin password is configured; the password itself is never stored.
- Rotate by generating a new hash, updating `.env` and restarting the app.

## 6. Backups and recovery

The app writes `neon-backup-<timestamp>.zip` into `<NEON_DB_DIR>/backups` every `BACKUP_INTERVAL_HOURS` and keeps
`BACKUP_KEEP` files. That protects against corruption and mistakes, **not** against losing the server. Copy them off-site:

```bash
# example nightly cron on the host (adjust the volume path with `docker volume inspect`)
VOL=$(docker volume inspect neon-guess-game_neon-data -f '{{ .Mountpoint }}')
sudo rsync -a "$VOL/backups/" backup-user@backup-host:/srv/neon-backups/
```

Restore:

```bash
docker compose stop app
VOL=$(docker volume inspect neon-guess-game_neon-data -f '{{ .Mountpoint }}')
sudo unzip -o /path/to/neon-backup-XXXX.zip -d "$VOL"      # restores scores.mv.db
# if the app cannot write afterwards, give the files back to the container's "neon" user
docker compose start app
```

Test a restore once before you rely on it. Backups contain consent records and pseudonymous analytics; protect them like the live data.

## 7. Verify a deployment (do this before announcing it)

```bash
BASE=https://game.example.com
curl -s $BASE/healthz                                   # {"status":"ok"}
curl -sI $BASE/ | grep -iE 'strict-transport|content-security|x-frame|nosniff'
curl -s -o /dev/null -w "%{http_code}\n" $BASE/api/analytics/summary   # 401 when not signed in
curl -s -o /dev/null -w "%{http_code}\n" -X POST -H "X-Requested-With: fetch" \
     -d "type=page_view&eventId=00000000-0000-4000-8000-000000000000&path=/" $BASE/api/analytics/event   # 403: no consent
curl -sI $BASE/admin/dashboard | head -1                # 302 to /admin/login
```

Then in a browser (private window): the banner appears; DevTools -> Application -> Cookies shows **no** `neon_vid`
before you accept; after "Accept all" it appears with `HttpOnly`, `Secure`, `SameSite=Strict`; the footer link reopens
settings; "Reject optional" removes `neon_vid` and `neon_prefs`; sign in at `/admin/login`, watch the numbers move
(play a game with analytics accepted in another browser), sign out, and confirm `/admin/dashboard` redirects.
Only after these pass should you claim that analytics works for all visitors.

## 8. Operations notes

- Logs go to the container's stdout (`docker compose logs app`). The app does not log IP addresses; your proxy and host
  may, so document their retention in `privacy.html`.
- Keep the OS and base images updated (`docker compose pull && docker compose up -d --build` periodically).
- Consider restricting `/admin/*` to your own IP at the proxy as an extra layer. The application already enforces
  authentication, so this is optional.
- Legal text, Impressum and processor agreements (hosting provider) are manual steps, see the README note.
