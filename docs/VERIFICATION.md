# Verification status

This records what was **actually executed** when this version was produced, and what you must run yourself.
The authoring environment had **no Maven, no network, no Tomcat/Docker, and no H2, Servlet API or JUnit jars**,
so the real build and the database-backed tests could **not** be run there.

## Executed in the authoring environment

| Check | Result |
|---|---|
| Compile all main code (Java 17 language level, `-source 17`) against a hand-written, signature-compatible stub of the Jakarta Servlet API | success, no errors |
| Compile all tests against stubs of the Servlet API and JUnit | success, no errors |
| Run 106 tests with a minimal JUnit-like runner (the 101-test core suite was run three times in a row) | 106 passed, 0 failed |
| Mutation checks (consent check removed, admin gate removed, HttpOnly removed) | each broke the expected tests, so the tests detect those regressions |
| Run the **real** V2 migration and the **actual SQL strings extracted from `AnalyticsRepository`** against SQLite with a visit/visitor/series/erase scenario | all assertions passed (SQLite, not H2) |
| Static review of H2-specific SQL pitfalls (reserved words, untyped parameters in `CASE`, generated keys) | one issue found and fixed |

The 106 executed tests are: `PasswordHasher`, `RateLimiter`, `LoginThrottle`, `AppConfig`, `ConsentState`,
`ConsentServlet`, `AnalyticsInput`, `AnalyticsEndpoints`, `LiveSessionRegistry`, `AdminSecurity`, the original
`GameLogic`/`Json`/`Validation` tests, the migration-script splitter, and the 4 game-flow tests that need no database.

## NOT executed (written, compile-checked, never run)

| Test class | Tests | Needs |
|---|---|---|
| `DatabaseTest` (original) | 8 | H2 |
| `DatabaseMigrationTest` (3 of 4) | 3 | H2 |
| `AnalyticsRepositoryTest` | 18 | H2 |
| `AnalyticsServiceTest` | 6 | H2 |
| `AnalyticsFlowTest` | 11 | H2 |
| `GameAnalyticsFlowTest` (5 of 9) | 5 | H2 |
| **Total** | **51** | |

Also never run: the Maven build and WAR packaging, `docker build`/`docker compose`, Tomcat deployment, the
browser UI (banner, dialog, dashboard chart), the Caddy HTTPS setup, `BACKUP TO`, and behaviour that only the real
container provides (Tomcat's `SameSite` cookie rendering, `changeSessionId`, session listener callbacks).
Because the stubs were written by me, they could hide a mismatch with the real Servlet API.

## What you should run

```bash
mvn -B verify                    # compiles against the real APIs, runs all 157 tests, builds the WAR
```

If a test fails, the most likely candidates are the 51 above (first contact with real H2). Typical causes would
be an H2 SQL dialect detail, or a timing assumption in the concurrency tests; the failure message will point to the statement.
Then:

1. `docker compose build` and `docker compose up`, open the site, check `/healthz`.
2. Manual browser checks from `docs/DEPLOYMENT.md` section 7.
3. Play a few games with analytics accepted, sign in to the dashboard, confirm the numbers and the 15 s refresh.
4. Confirm cookie attributes in DevTools on your real HTTPS domain.
