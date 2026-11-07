# 3D Neon Guess the Number

A full-stack Java web app: a neon, 3D-styled "Guess the Number" game backed by Jakarta Servlets,
JDBC and an embedded H2 database, extended with **privacy-conscious live analytics**, **cookie
consent**, a **secure admin dashboard**, and **Docker deployment**. All game rules and the secret
number live on the server.

- Java 17, Jakarta Servlet 6, Apache Tomcat 10.1+
- Maven (WAR), JDBC, H2, JUnit 5
- HTML5, CSS3, vanilla JavaScript (Fetch API + JSON), no front-end framework and no runtime
  dependencies beyond H2 and the servlet API

## Contents

1. [Features](#features) · 2. [Architecture](#architecture) · 3. [Project structure](#project-structure)
4. [Run locally](#run-locally) · 5. [Admin setup](#admin-setup) · 6. [Configuration](#configuration-environment-variables)
7. [Cookies and consent](#cookies-and-consent) · 8. [Analytics model](#analytics-model-what-the-numbers-mean)
9. [API](#api) · 10. [Database schema](#database-schema) · 11. [Security](#security-notes)
12. [Tests](#tests) · 13. [Deployment](#deployment) · 14. [Backups](#backup-and-recovery) · 15. [Limitations](#limitations)

## Features

**Game (unchanged)**

- Secret number (1-50) generated on the server with `SecureRandom`, kept in `HttpSession`
- 10 attempts, too-high / too-low / "really close" hints (within 3), win/lose messages, restart
- Player names, attempts left, total tries, animated progress bar, confetti on a win
- Finished games saved to H2 through JDBC; recent-scores and top-scores leaderboard
- Duplicate-result prevention (session flag + `UNIQUE(game_id)` constraint)
- Responsive, accessible UI (labels, `aria-live`, skip link, focus styles, reduced-motion support)

**New**

- Cookie banner and settings panel: *Accept all*, *Reject optional*, *Customize*, reopen from the footer,
  consent versioning, withdrawal that actually deletes data
- Analytics that only exist after consent: unique visitors, visits, page views, active visitors,
  game started/won/lost, win rate, average attempts, daily/weekly figures, activity chart
- Admin-only dashboard (server-side authorization, PBKDF2 passwords from the environment, CSRF
  tokens, idle + absolute session expiry, login throttling, rate limits) that refreshes every
  10-15 s through JSON calls, with no page reload
- In-memory live gauges of container sessions (open, active, ongoing games, logged-in admins)
- Versioned SQL migrations, retention job, rolling database backups, `/healthz`
- Dockerfile, Docker Compose, optional Caddy HTTPS, GitHub Actions CI

## Architecture

```
 Browser  index.html + consent.js (+ analytics.js only after consent) + script.js
    |  fetch() JSON                                  Admin browser: /admin/login -> /admin/dashboard
    v                                                          |
 +--------------------------------- Tomcat 10.1 ---------------------------------------+
 | SecurityHeadersFilter (CSP, HSTS in prod, nosniff, frame, permissions headers)       |
 |                                                                                      |
 |  public                      consent-gated               admin only (AdminServlet)   |
 |  GameServlet  /game          AnalyticsEventServlet       SummaryServlet              |
 |  HistoryServlet /history       /api/analytics/event      /api/analytics/summary      |
 |  ConsentServlet              HeartbeatServlet            TimeseriesServlet           |
 |    /api/cookie-consent         /api/analytics/heartbeat  /api/analytics/timeseries   |
 |  HealthServlet /healthz                                  DashboardServlet            |
 |                                                          /admin/dashboard            |
 |  LoginServlet /admin/login   LogoutServlet /admin/logout                             |
 |                                                                                      |
 |  AppContext (wiring) -> AnalyticsService -> AnalyticsRepository (JDBC, transactions) |
 |  LiveSessionRegistry (in-memory gauges)   AdminAuth (sessions, CSRF, throttle)       |
 |  RateLimiter / LoginThrottle (in-memory)  MaintenanceScheduler (sweep, retention,    |
 |                                           backups)                                   |
 +------------------------------------------|-------------------------------------------+
                                            v
                  H2 file database (NEON_DB_DIR, default ~/.neon-guess-game/scores)
```

| Layer     | Package / files                                                                   |
|-----------|-----------------------------------------------------------------------------------|
| UI        | `index.html`, `privacy.html`, `css/*.css`, `js/script.js`, `consent.js`, `analytics.js`, `admin.js`, `WEB-INF/admin/*.html` |
| Game      | `GameServlet`, `GameLogic`, `HistoryServlet`, `Validation`, `Json` (unchanged rules) |
| Config    | `config.AppConfig` (environment variables / system properties)                    |
| Privacy   | `privacy.ConsentServlet`, `ConsentState`, `Consents`, `CookieSupport`, `ConsentLog` |
| Analytics | `analytics.*` (servlets, `AnalyticsService`, `AnalyticsRepository`, `LiveSessionRegistry`, `MaintenanceScheduler`) |
| Admin     | `admin.*` (`AdminServlet` base class, `AdminAuth`, login/logout/dashboard, `AuditLog`) |
| Security  | `security.*` (`PasswordHasher`, `RateLimiter`, `LoginThrottle`, `ClientIp`)       |
| Data      | `Database`, `Migrator`, `src/main/resources/db/migration/*.sql`                   |

## Project structure

```
neon-guess-game/
|-- pom.xml, README.md, .env.example, .gitignore, .dockerignore
|-- Dockerfile, docker-compose.yml, Caddyfile
|-- .github/workflows/ci.yml
|-- docs/DEPLOYMENT.md, docs/VERIFICATION.md
`-- src/
    |-- main/java/com/vedashree/game/
    |   |-- (original game classes) + AppContext, Migrator, HealthServlet
    |   |-- config/ security/ privacy/ analytics/ admin/
    |-- main/resources/db/migration/   V1__game_results.sql, V2__analytics.sql
    |-- main/webapp/                   index.html, privacy.html, css/, js/, WEB-INF/{web.xml,admin/}
    `-- test/java/com/vedashree/game/  (see Tests)
```

## Run locally

Prerequisites: JDK 17+, Maven 3.9+.

```bash
mvn test                      # run the tests
mvn package cargo:run         # starts Tomcat 10.1 via Cargo
# open http://localhost:8080/neon-guess-game/
```

Alternatively `mvn clean package` and drop `target/neon-guess-game.war` into Tomcat 10.1's `webapps/`,
or use Docker: `cp .env.example .env`, edit it, then `docker compose up -d --build`.

In development (`APP_ENV` unset) cookies are not marked `Secure`, so plain `http://localhost` works.
Start with `ADMIN_*` unset and the game runs normally; only the dashboard stays disabled.

## Admin setup

The admin area is **off until you configure credentials**. There is no default account and no
password in the code or repository. Only a salted PBKDF2 hash is stored in the environment.

1. Create a hash (the password is never echoed or stored):
   ```bash
   mvn -q compile
   java -cp target/classes com.vedashree.game.security.PasswordHasher
   # prints: pbkdf2_sha256:600000:<salt>:<hash>
   ```
   Use at least 12 characters. In Docker you can pipe it: `echo 'my long password' | java -cp ... PasswordHasher`.
2. Provide both values as environment variables (shell, `.env`, or your host's secret store):
   ```bash
   export ADMIN_USERNAME=owner
   export ADMIN_PASSWORD_HASH='pbkdf2_sha256:600000:...'
   mvn package cargo:run
   ```
   PowerShell: `$env:ADMIN_USERNAME="owner"; $env:ADMIN_PASSWORD_HASH="pbkdf2_sha256:..."`.
3. Open `/admin/login`, sign in, and you land on `/admin/dashboard`.

Sign-in is throttled (5 failures lock that client for 15 minutes), an idle admin is signed out after
`ADMIN_IDLE_MINUTES` (dashboard polling deliberately does **not** extend this), and every session ends
after `ADMIN_MAX_SESSION_MINUTES` regardless of activity.

## Configuration (environment variables)

Variables can also be set as JVM properties: `ADMIN_USERNAME` becomes `-Dneon.admin.username=...`
(lower-case, `_` to `.`). Properties win over environment variables. Invalid values fall back to the
default and are logged. See `.env.example` for a ready template.

| Variable | Default | Meaning |
|---|---|---|
| `APP_ENV` | `development` | `production` enables Secure cookies and HSTS |
| `COOKIE_SECURE` | `true` in production, else `false` | Force the `Secure` flag on or off |
| `TRUST_PROXY` | `false` | Use the last `X-Forwarded-For` entry (set `true` only behind your own proxy) |
| `ADMIN_USERNAME`, `ADMIN_PASSWORD_HASH` | unset | Both required to enable the admin area |
| `ADMIN_IDLE_MINUTES` / `ADMIN_MAX_SESSION_MINUTES` | 15 / 120 | Admin idle and absolute timeouts |
| `SESSION_TIMEOUT_MINUTES` | 30 | Inactivity timeout of the game session (HttpSession) |
| `ANALYTICS_ACTIVE_WINDOW_SECONDS` | 300 | Window that defines an "active visitor" |
| `ANALYTICS_VISIT_TIMEOUT_MINUTES` | 30 | Inactivity after which a visit ends |
| `ANALYTICS_RETENTION_DAYS` | 365 | Raw analytics older than this are deleted hourly |
| `ANALYTICS_COOKIE_DAYS` | 180 | Lifetime of the visitor cookie |
| `DASHBOARD_REFRESH_SECONDS` | 15 | Dashboard polling interval (10-60, UI clamps to 10-15) |
| `CONSENT_POLICY_VERSION` | 1 | Bump when the cookie/privacy text changes: everyone is asked again |
| `CONSENT_MAX_AGE_DAYS` / `CONSENT_LOG_RETENTION_DAYS` | 180 / 1095 | Consent cookie lifetime / proof-of-consent retention |
| `NEON_DB_DIR` (or `-Dneon.db.dir`) | `~/.neon-guess-game` | Folder of the H2 database |
| `BACKUP_INTERVAL_HOURS` / `BACKUP_KEEP` / `BACKUP_DIR` | 24 / 7 / `<db dir>/backups` | Rolling backups (0 hours disables) |

If you change cookie lifetimes, update the matching text in `js/consent.js` and `privacy.html`.

## Cookies and consent

| Cookie | Category | Purpose | Lifetime | Flags |
|---|---|---|---|---|
| `JSESSIONID` | Necessary | Server-side game state (and admin login for the owner) | session / inactivity timeout | HttpOnly, SameSite=Strict, Secure in prod |
| `neon_consent` | Necessary | Remembers the cookie decision and policy version | 180 days | HttpOnly, SameSite=Strict, Secure in prod |
| `neon_vid` | Analytics (opt-in) | Random anonymous visitor id | 180 days | HttpOnly, SameSite=Strict, Secure in prod |
| `neon_prefs` | Preferences (opt-in) | Remembered player name and leaderboard sort | 180 days | SameSite=Strict, Secure on https (readable by JS on purpose) |

How it works:

- The **server** owns the decision. `POST /api/cookie-consent` writes the `neon_consent` cookie; the analytics
  endpoints re-check that cookie on every request, so bypassing the JavaScript does not bypass consent.
- `analytics.js` is **not loaded** until the server confirms analytics consent, and stops when it is withdrawn.
- **Withdrawal** deletes `neon_vid`, deletes `neon_prefs` if preferences are withdrawn, and erases the visitor's
  analytics rows (visits and page views). Game events stay as anonymous counts.
- A decision made for an older `CONSENT_POLICY_VERSION` counts as "no decision" and the banner reappears.
- Every decision is appended to `consent_log` (random consent id, choice, version, time; no IP, no user agent).
- *Accept all* and *Reject optional* have identical size and styling; nothing optional is pre-selected.
- Necessary cookies are limited to what the game needs; analytics stays opt-in.

> **Not legal advice.** This is a privacy-by-design implementation aimed at GDPR/ePrivacy (TDDDG section 25) expectations
> for visitors in Germany and the EU. It does not guarantee compliance. Before going live: fill the
> `[PLACEHOLDERS]` in `privacy.html`, add the legal notice (Impressum) German law requires for most sites, check your
> host's data-processing terms and log retention, and have the texts reviewed for your situation.

## Analytics model (what the numbers mean)

| Term | Meaning |
|---|---|
| **Visitor / unique visitor** | An anonymous browser holding the `neon_vid` cookie. Counted once. Only visitors who accepted analytics. |
| **Visit** | Run of activity by one visitor; ends after `ANALYTICS_VISIT_TIMEOUT_MINUTES` of inactivity |
| **Page view** | One page load, reported once by `analytics.js` with a unique `eventId` (replays are ignored) |
| **Heartbeat** | Sent every 30 s while the tab is visible and the person is active; extends the visit, never a page view |
| **Active visitor** | Visitor with activity inside `ANALYTICS_ACTIVE_WINDOW_SECONDS`. **A live estimate**, not an exact count. |
| **Game events** | `STARTED`, `WON`, `LOST`; written **by the server** (not trusted from the browser), once per game, consented visitors only |
| **Ongoing game sessions** | Container sessions with an unfinished game (in memory, includes visitors who declined analytics) |
| **Logged-in admins** | Admin sessions that are authenticated and not expired. Visitors are never "logged in users". |
| **Registered users** | Not applicable: the game has no public accounts (the dashboard says so) |

Live numbers are estimates because browsers close without notice; a visitor remains "active" until the window passes.
Visitors who reject analytics are not counted, so real traffic is higher than shown. Chart buckets are UTC.
The leaderboard totals (all saved games, every player) are shown separately from consented game analytics.

## API

All responses are JSON with `Cache-Control: no-store`; errors look like `{"error":"message"}`.
`POST` endpoints need the header `X-Requested-With: fetch` and form-encoded bodies.

### Game (unchanged)

| Method | Path | Parameters | Success | Errors |
|---|---|---|---|---|
| GET | `/game` | none | 200 | |
| POST | `/game` | `action=start`, `name` (optional) | 201 | 400, 403 |
| POST | `/game` | `action=guess`, `guess` (1-50) | 200 | 400, 403, 409 |
| GET | `/history` | `limit` (1-50, default 10), `sort` (`recent`/`top`) | 200 | 400, 500, 503 |
| GET | `/healthz` | none | 200 (`ok`) | 503 (`degraded`) |

### Consent, analytics, admin

| Method | Path | Auth | Parameters | Responses |
|---|---|---|---|---|
| GET | `/api/cookie-consent` | none | | 200 `{decided, analytics, preferences, policyVersion, decidedAt}` |
| POST | `/api/cookie-consent` | fetch header | `analytics`, `preferences` = `true`/`false` | 200; 400 invalid; 403 header; 429 |
| POST | `/api/analytics/event` | consent cookie + fetch header | `type=page_view`, `eventId` (UUID), `path` (no query string) | 201 recorded; 200 duplicate; 400; 403 no consent; 429; 503 |
| POST | `/api/analytics/heartbeat` | consent cookie + fetch header | none | 204 extended; 409 no live visit (send a page view); 403; 429 |
| GET | `/api/analytics/summary` | **admin session** | none | 200 aggregates; 401; 429; 503 |
| GET | `/api/analytics/timeseries` | **admin session** | `range=24h\|7d\|30d` (default `7d`) | 200 zero-filled points; 400; 401 |
| GET/POST | `/admin/login` | none | `username`, `password`, `csrf` | 200 form / 302 redirect |
| POST | `/admin/logout` | session + CSRF token | `csrf` | 302 |
| GET | `/admin/dashboard` | **admin session** | | 200 HTML; 302 to login |

Game events are deliberately **not** accepted from the browser: `GameServlet` records them server-side.
No analytics API ever returns visitor ids, session ids or per-visitor history.

```bash
BASE=http://localhost:8080/neon-guess-game
# consent, then a page view (cookie jar keeps neon_consent / neon_vid)
curl -c jar -b jar -H "X-Requested-With: fetch" -d "analytics=true&preferences=false" $BASE/api/cookie-consent
curl -c jar -b jar -H "X-Requested-With: fetch" \
     -d "type=page_view&eventId=$(uuidgen)&path=/" $BASE/api/analytics/event
curl -i $BASE/api/analytics/summary        # 401: not signed in
```

## Database schema

Migrations live in `src/main/resources/db/migration` and run at startup (tracked in `schema_version`;
every script is idempotent, existing `game_results` data is never touched).

| Table | Purpose | Key indexes |
|---|---|---|
| `game_results` | Leaderboard (original table) | `played_at DESC`, `UNIQUE(game_id)` |
| `analytics_visitors` | Anonymous visitor ids (consent only) | `first_seen_ms`, `last_seen_ms` |
| `analytics_visits` | Visit lifecycle: `started_ms`, `last_activity_ms`, `ended_ms`, `end_reason` | `(visitor_id, ended_ms)`, `(ended_ms, last_activity_ms)`, `started_ms` |
| `analytics_page_views` | One row per page load, `UNIQUE(event_id)` | `viewed_ms` |
| `game_events` | `STARTED`/`WON`/`LOST`, `UNIQUE(game_id, event_type)`, visitor nullable | `occurred_ms` |
| `consent_log` | Append-only proof of consent decisions | `decided_ms`, `consent_id` |
| `admin_audit` | Admin login/logout/expiry events (no IP, no typed usernames) | `occurred_ms` |
| `schema_version` | Applied migrations | |

Timestamps are UTC epoch milliseconds (`BIGINT`). Deleting a visitor cascades to their visits and page views and
sets `game_events.visitor_id` to `NULL`. No raw IP addresses, user agents or fingerprints are stored anywhere.
**Retention:** raw analytics after `ANALYTICS_RETENTION_DAYS`; consent log after `CONSENT_LOG_RETENTION_DAYS`;
admin audit after 180 days. A background job ends idle visits every minute and applies retention hourly.

## Security notes

- The secret never leaves the server before the game is over. All parameters are validated; all SQL uses
  `PreparedStatement` (the only concatenated SQL text is a bucket size restricted to two constants).
- Admin authorization is enforced server-side in `AdminServlet.service()` (final, deny by default); the dashboard
  HTML is served from `/WEB-INF` only after that check. Hiding buttons is never relied upon.
- PBKDF2-HMAC-SHA256 (600,000 iterations), constant-time comparisons, equal work for unknown usernames,
  session id change on login (fixation), rotating CSRF tokens, 401 JSON for APIs, no data in error bodies.
- CSRF: admin POSTs need a session-bound token; public JSON POSTs need the custom `X-Requested-With` header;
  cookies are `SameSite=Strict`.
- Rate limits (in memory): analytics 120 requests/min and 20 new visitors/min per client, consent 30/min,
  admin 120/min, login lock after 5 failures. IP addresses used for this are never stored or logged.
- Headers: CSP without inline code, `nosniff`, `X-Frame-Options: DENY`, `Referrer-Policy: no-referrer`,
  Permissions-Policy, COOP/CORP, HSTS in production, `no-store` on all JSON and admin pages.
- Secrets: nothing is hardcoded; `.env` and database files are git-ignored.

## Tests

```bash
mvn test            # all unit and integration tests (in-memory H2, no server needed)
mvn verify          # tests + WAR packaging (what CI runs)
```

| Class | Covers |
|---|---|
| `GameLogicTest`, `ValidationTest`, `JsonTest`, `DatabaseTest` | original game rules and persistence (unchanged) |
| `GameAnalyticsFlowTest` | game works with/without DB and consent; server-side game events; no double counting; analytics failure never breaks the game |
| `ConsentStateTest`, `ConsentServletTest` | cookie format, strict parsing, attributes (HttpOnly/SameSite/Secure), withdrawal deletes cookies, policy-version re-prompt, rate limit |
| `AnalyticsInputTest`, `AnalyticsEndpointsTest` | validation, CSRF header, no tracking without/after-withdrawn/stale consent, 400/403/429/503 |
| `AnalyticsRepositoryTest` | visit creation/reuse/timeout, duplicate events, heartbeat semantics, active/idle, retention, erase, concurrency |
| `AnalyticsServiceTest` | zero-filled series, rates, summary contains no ids |
| `AnalyticsFlowTest` | browser-like journeys: consent -> page view -> heartbeat -> expiry -> withdrawal; admin APIs |
| `AdminSecurityTest` | 401/302 for anonymous, forged sessions, login hardening, throttle, CSRF, idle/absolute expiry, logout |
| `LiveSessionRegistryTest` | session gauges, game sessions, admin vs visitor, expired vs ended, id change |
| `DatabaseMigrationTest` | idempotent migrations, upgrade of an old database in place |
| `PasswordHasherTest`, `RateLimiterTest`, `LoginThrottleTest`, `AppConfigTest` | security primitives and configuration |

After deploying, verify manually (see `docs/VERIFICATION.md` for the full checklist).

## Deployment

See **[docs/DEPLOYMENT.md](docs/DEPLOYMENT.md)**: Docker, Compose, HTTPS with Caddy, provider options, secrets, health
checks, backups. Short version:

```bash
cp .env.example .env            # fill ADMIN_*, set APP_ENV=production, TRUST_PROXY=true behind a proxy
docker compose --profile https up -d --build     # with DOMAIN=your.domain in .env
```

Static hosts (GitHub Pages, Netlify, Vercel static) cannot run servlets or keep a database: they can host
the HTML only and will have no game backend, consent API, or analytics.

## Backup and recovery

A background job writes `neon-backup-<UTC timestamp>.zip` (H2 online `BACKUP TO`) every `BACKUP_INTERVAL_HOURS` and keeps the
newest `BACKUP_KEEP`. **Copy these off the server** (object storage, another machine), because a backup on the same
disk does not protect against losing the disk. Restore: stop the app, unzip a backup into `NEON_DB_DIR` (it contains
`scores.mv.db`, replacing the existing file), start the app; migrations bring older backups up to date.

## Limitations

- **One application instance.** The embedded H2 file database is single-process and visitor locks are per JVM. To scale
  out, move to PostgreSQL/MySQL (SQL is standard; replace `Database.backupTo`, add a pool) and lock on the visitor row.
- Live counts are **estimates**; session gauges are in memory (reset on restart; sessions restored from disk by Tomcat are not listed).
- Only consenting visitors appear in visitor/visit/page-view/game analytics; there is no bot filtering.
- Chart buckets are UTC; "Expired" vs "Ended" session counters are told apart by inactivity age.
- The login throttle is per client address (shared NAT shares a counter); behind a proxy set `TRUST_PROXY=true`.

## License

MIT (add a LICENSE file if you publish this).
