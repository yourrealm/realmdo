# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with
code in this repository.

## What this is

realmdo is a per-user todo app deployed as a Realm app. Three independent parts
share one JSON API:

- `server/`: Rust (axum + rusqlite) JSON API over one SQLite file. It is the
  only thing in the Docker image (`ghcr.io/yourrealm/realmdo`, distroless, no
  shell).
- `realm.tsx`, `widgets/`, `pages/`: the Realm SDK (`@yourrealm/sdk`) manifest
  plus a Home widget (`widgets/tasks.tsx`) and a settings page
  (`pages/settings.tsx`). These describe the deployment and UI. They never run
  inside the container. Deno typechecks and formats them, and pnpm installs the
  SDK.
- `android/`: a thin Kotlin/Compose client (minSdk 31) that signs in through the
  realm's Home and talks to `/api/` as that user.

There is no web UI anymore. Leftover `web/` and `dist/` directories are
untracked build artefacts.

## Scope

- Per-user worlds: each user sees only their own tasks. There is no sharing, no
  assignment and no collaboration.
- Better, not bigger: no subtasks, no kanban and no projects.
- The server sends no reminders or notifications. Those belong to the Android
  app.
- Notes and steps never appear in `/public/` or the Live Update. The URL secret
  is a weaker credential than a session or a token.

## Commands

```sh
pnpm install                 # SDK + esbuild (JSR registry via .npmrc)
pnpm typecheck               # deno check + cargo clippy -D warnings
pnpm test                    # realm-sdk test (realm.test.ts) + cargo test
pnpm fmt / pnpm fmt:check    # deno fmt + cargo fmt
pnpm validate                # realm-sdk validate realm.tsx
pnpm build:pack              # dist/realmdo.realmpack
pnpm dev                     # realm-sdk dev
pnpm dev:server              # run the API locally on :3000 (REALMDO_PORT overrides), data in server/data
pnpm android:build           # testDebugUnitTest lintDebug assembleDebug
pnpm android:install
```

Single tests:

```sh
cargo test --manifest-path server/Cargo.toml session_header_identifies_the_user   # by name substring
cd android && ./gradlew testDebugUnitTest --tests 'me.malm.realmdo.AuthTest'
```

CI (`.github/workflows/ci.yml`) runs three jobs that do not depend on each
other: `container` (cargo fmt/clippy/test), `realm` (deno fmt/check, realm-sdk
test, build pack), and `android` (JDK 17). On main it also pushes a multi-arch
image.

## Identity and auth (spans realm.tsx and server/src/api.rs)

- **One identity**: the Realm gate injects `X-Realmdo-User: <username>`
  (`getTrustedHeaders` in realm.tsx) and strips any copy the client sent. The
  server trusts this header on every request and creates the user row the first
  time it sees it. The header name in `api.rs` (`USER_HEADER`) must match
  realm.tsx.
- **The phone** signs in through Realm's native clients: OAuth with PKCE against
  Home, tokens `realm_at_…` and `realm_rt_…`. The gate checks the token, strips
  it and injects the same header a browser gets, so the server never sees a
  token. The flow is holden's `realm-ts/native-clients` docs page.
- **Public URL** (`/public/pub_…/today`): a read-only secret URL for TRMNL,
  stored only as a SHA-256 hash (`secret.rs`).
- The gate in realm.tsx admits anonymously on shape only: `/public/` with its
  secret. Everything else needs a signed-in user. On an anonymous admit the
  header is absent, which Realm guarantees only since holden `d0c669ff`
  (2026-09-17). Native clients need holden `70b184e5`. The server does not guard
  against older behaviour, so an older Realm cannot host this app.
- Never use `r.home.publicDomain()`. Keep `router.rateLimit` tight, since the
  public-secret check is the exposed surface.
- Widget and page loaders/actions must never declare headers. Identity always
  comes from the brokered header, and `realm.test.ts` asserts this.

## Why things are the way they are

- **Rust** for the footprint: the app idles on the box forever. A Deno container
  idles near 90 MiB, while this binary idles near 2.4 MiB (target: under 10
  MiB).
- **glibc and `distroless/cc`, not musl**: rusqlite's bundled `sqlite3.c` needs
  a C cross-compiler, and Debian's `gcc-*-linux-gnu` packages are the
  well-trodden one. Build stages run on `$BUILDPLATFORM` and cross-compile per
  `$TARGETARCH` for both amd64 and arm64.
- **TRMNL polls** `/public/{secret}/today`. Pushing to TRMNL's cloud (the
  Webhook strategy) would not update the device sooner, since it only pulls on
  its own schedule, and it would cost an egress grant, a stored key and a 2 KB
  payload cap.
- **Android stays lean**: `HttpURLConnection` (which accepts PATCH) and
  `org.json`, with no Room, Retrofit, WorkManager, AppAuth or navigation
  library. `androidx.browser` is only there for the Custom Tab. `App` in
  `MainActivity.kt` picks one screen from state. Line icons are paths in
  `Glyphs.kt`, not the extended icon library.

## Server model

- **Task kinds** (`db::Kind`): `once` (done means archived), `interval` (recurs,
  measured from the last completion), and `slot` (daily, one check per slot out
  of morning, before_lunch, afternoon and evening, with per-user start times in
  `slots.rs`). A task can also carry timed `steps` (a routine that the Android
  player speaks aloud) and `notes`.
- **Urgency** (`urgency.rs`) is computed on read and never stored. It is below 0
  when hidden and ≥1 when "important". A dated one-off shows up `lead_days`
  before its due date (7 when null, 0 means the day itself). The user's manual
  order is `tasks.position` (`PUT /api/tasks/order`).
- **Time**: every "today" and "now" is in the user's IANA time zone (`clock.rs`,
  via jiff, falling back to UTC). Calendar days are integer days since the epoch
  (`date.rs`), and completions store the local `day` key and `slot`.
- **Migrations**: `db::MIGRATIONS` is an append-only array keyed by
  `PRAGMA user_version`. Add a new entry at the end and never edit an existing
  one.
- **Tests** live in `#[cfg(test)]` modules in each file. API tests build the
  router with `AppState::with_clock(db::open_memory(), fixed_now)` and drive it
  with `tower::ServiceExt::oneshot`.
- The binary also runs as `realmdo healthcheck`, which is the container
  healthcheck (exec form, because the image has no shell). Env: `PORT` (3000)
  and `REALMDO_DATA_DIR` (`/data`).
- The server enforces input limits (`MAX_*` in `api.rs`). `android/.../Draft.kt`
  mirrors them, so change both together.

## Android

- Package `me.malm.realmdo`. The connect screen takes Home's address, typed or
  scanned from the QR on Home's Phones page with the Play services code scanner
  (so no camera permission). It never accepts the companion's `realm://enroll`
  code. `Auth` registers once per Home, opens the consent page in a Custom Tab,
  and takes the redirect to `me.malm.realmdo:/callback` in `MainActivity`. The
  token answer's `services.api` is the base URL.
- `Prefs` holds the tokens, outside backups. `Session` refreshes them under one
  lock, since every refresh rotates and a reused refresh token disconnects the
  phone. `Api` refreshes and retries once on Realm's 401, and drops the tokens
  only on `invalid_grant`.
- A notification shows the top task and is promoted to a Live Update on API 36.
  It is kept fresh by an alarm chain (`TaskActionReceiver`, `BackgroundRefresh`)
  and re-posted after a reboot by `BootReceiver`.
- JVM unit tests only. Anything that needs to be testable avoids `android.*`
  APIs (for example, `Auth` parses with `java.net.URI`, and tests use the real
  `org.json`).

## Conventions

- Don't commit or push unless asked. Work on `main`, with no feature branches.
- Relative imports carry `.ts`/`.tsx` extensions. No `npm:` or `jsr:` prefixes
  in imports: dependencies live in `package.json`.
- Short code comments. No backwards-compat shims, no dynamic imports, and don't
  export what nothing imports.
- Write docs in plain language: conclusion first, one idea per sentence, no em
  dashes.
