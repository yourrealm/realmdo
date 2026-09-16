# CLAUDE.md

Guidance for Claude Code working in this repository.

## What this is

**realmdo** - a todo app for Realm. It is a Realm app: one `realm.tsx`
describing the deployment, one Docker image (Rust backend + embedded React web
UI), and home-screen widgets. Realm itself lives at `../holden`
(`/Users/max/dev/holden`). Read that repo's `CLAUDE.md` before touching anything
here; this file only records what is specific to realmdo.

Status (2026-08-30): layout and toolchains stand and are verified end to end
(SDK link, Rust server, web UI, both image arches, container smoke test). All
three task kinds are built, per user, with urgency computed on read in the
user's time zone and a completion history. Per-user settings hold the time zone
and slot start times. Bearer tokens are done (minted under Settings, hashed,
session-only management). One widget exists (`widgets/tasks.tsx`: ranked list,
done, add). Tasks are editable (PATCH, inline in the web UI, kind fixed).
`/public/{secret}/today` serves the TRMNL pull fallback; the push route and the
Android client are not started. The decisions below are the spec. When one
changes, change it here.

## Where to look in the Realm repo

| Path (under `../holden`)                                           | Why                                                                                                                   |
| ------------------------------------------------------------------ | --------------------------------------------------------------------------------------------------------------------- |
| `CLAUDE.md`                                                        | Architecture, conventions, the compile pipeline, permissions and grants.                                              |
| `apps/widget-pack/`                                                | The closest template: container API + `widgets/*.tsx`, distroless image, exec-form healthcheck, both-arch Dockerfile. |
| `apps/miniflux/realm.ts`                                           | `getTrustedHeaders` in practice: auto-create a local user from the brokered header.                                   |
| `packages/sdk/src/create-app.ts`                                   | `User` type, `getTrustedHeaders`, `verifyAnonymousRequest` (signature and rules).                                     |
| `platform/home/src/server/access-gate.ts`                          | What the gate really does: an admitted anonymous request carries **no** brokered identity.                            |
| `platform/home/src/server/run-loader.ts`                           | What a widget loader call carries to the container (resolved refs + `X-Realm-Widget-PID`, no viewer).                 |
| `packages/sdk/src/ui-surface.ts`                                   | Loader / action / widget / page types.                                                                                |
| `packages/sdk/src/resolve.ts`                                      | Router schema (`rateLimit`, `headers`, `tile`), `backupStrategySchema` (`sqlite`).                                    |
| `platform/docs/src/content/docs/realm-ts/`                         | The spec: `auth.mdx`, `ui.mdx`, `routing.mdx`, `info.mdx`, `testing.mdx`, `secrets.mdx`.                              |
| `platform/updater/`                                                | The Rust precedent: size argument, cross-compile off `$BUILDPLATFORM`.                                                |
| `todo/ideas/{multi-user-app-governance,smtp,alarms-and-digest}.md` | Platform features this app may consume later. None are built.                                                         |

## Decisions

### Product

1. **Per-user worlds.** Every user sees only their own tasks. No sharing, no
   assignment, no crossover between users. Identity is the Realm user brokered
   by `getTrustedHeaders` (username header); the backend creates its local row
   on first request.
2. **No reminders or notifications here.** They belong to the Android app.
3. **Mobile** is a thin Android client, built later, against the JSON API. No
   iOS.
4. **TRMNL** (e-ink) shows today's list. Prefer push: the backend POSTs merge
   variables to the TRMNL webhook. That needs `r.host.egress()` and the
   operator's TRMNL key as a secret config field. Pull via `/public/` is the
   fallback.
5. Better, not bigger. No subtasks, no kanban, no projects, no collaboration.

### Task model

Three kinds. Every task belongs to one user.

1. **One-off.** Title, optional due date. Done means archived.
2. **Interval** ("creeps up the list"). Recurs, measured from the last
   completion. Two numbers:
   - `after`: days the task stays hidden after completion.
   - `ramp`: days from first appearing until it is **important**. Example: clean
     bathroom, `after 6`, `ramp 5`: hidden days 0-6, appears on day 6, reaches
     important on day 11, keeps rising after that. "Workout every 3 days" is
     `after 3` with a default ramp. Completing resets the clock.
3. **Slot** (daily, time of day). One or more slots per day: morning,
   before-lunch, afternoon, evening. Each slot on each day is its own check.
   Example: brush teeth, before-lunch + evening. Resets daily. Slot times are
   per-user settings.

**Urgency** is one number per task, computed on read, never stored. The main
view is one list per user sorted by urgency, descending; a task below 0 is
hidden. Sketch, to be settled against real use:

- one-off: undated sits at a fixed middle value; dated rises to 1 at the due
  date and keeps rising past it.
- interval: `(days since completion - after) / ramp`. As built: the clock starts
  at creation until the first completion, so a new interval task is hidden for
  `after` days; `ramp` defaults to `after` (important once a whole interval
  overdue); `after` may be 0, `ramp` at least 1. Every completion is a row in
  `completions`; an interval task is never archived.
- slot: today's upcoming slots show in a "today" strip; the current slot is at
  the top; a passed unchecked slot counts as overdue until the day ends. As
  built: a slot runs from its start time to the next slot's start (evening to
  midnight); per slot: done, upcoming, current, overdue; the task's urgency is
  the most pressing slot: overdue 1.5, current 1.0, upcoming -0.5 (hidden from
  ranked lists, shown in the strip), all done -1. A bare "done" checks the
  overdue slot first, then current, then the next upcoming; a named slot can be
  checked ahead. One completion row per (local day, slot).

"Important" is the visual state at urgency >= 1.

### Stack

1. **Backend: Rust.** One binary, SQLite (bundled rusqlite), embeds the web UI.
   Target under 10 MiB idle (measured: 2.2 MB binary, 2.4 MiB idle RSS). Chosen
   over Go for consistency with the Realm repo's existing Rust crate and gate
   (`cargo clippy -D warnings`, `cargo fmt --check`, `cargo test`). A Deno
   container idles near 90 MiB; this app idles forever on the box, so the
   footprint matters.
2. **Web UI: React + Vite**, compiled to static files embedded in the binary.
   Served at the app's gated subdomain, which is where the brokered identity
   arrives. Keyboard-first, small.
3. **Widgets:** `widgets/*.tsx` on the Realm UI surface, stitched by
   `realm-sdk build`. Loaders call the `api` service; per-tile `configSchema`
   chooses what the tile shows; actions add and toggle straight from the tile.
   Since 2026-08-29 Home stamps the `getTrustedHeaders` mapping onto every
   loader and action call, so a widget reaches the container as its viewer and
   needs no identity of its own. Never declare `X-Realmdo-User` in a loader's
   `headers`: Home drops colliding names (any casing) in favour of its own, and
   declaring it would suggest the widget can pick a user.
4. **Gate:** `verifyAnonymousRequest` admits on request shape, nothing else.
   - `/api/`: admitted when an `Authorization: Bearer` header is present. The
     gate cannot verify per-user tokens (it runs in QuickJS with only method,
     URL and headers), so it only opens the door; the backend checks the token.
   - `/public/`: per-user opaque secret in the URL, for callers that cannot set
     headers.
   - Everything else needs a Home session. Never `r.home.publicDomain()`.
   - Set a tighter `router.rateLimit`; the token check is the exposed surface.
5. **Data:** SQLite file in a relative volume (`./data`). Declare the `sqlite`
   backup strategy so Realm Backup snapshots it consistently.
6. **Image:** both `linux/amd64` and `linux/arm64`, build stages pinned to
   `--platform=$BUILDPLATFORM` and cross-compiled per `$TARGETARCH`, distroless
   runtime, exec-form healthcheck (`["CMD", "/app/realmdo", "healthcheck"]`,
   there is no shell). glibc targets and `distroless/cc`, not musl and
   `distroless/static` like the updater: rusqlite's bundled `sqlite3.c` needs a
   C cross-compiler, and Debian's `gcc-*-linux-gnu` packages are the
   well-trodden one. Published to Docker Hub as `benjick/realmdo`; `realm.tsx`
   references it by tag. Measured 51 MB.
7. **`info`** on the app summary page: the API base URL and a "create a token
   under Settings" hint.

### Time, as built

1. Every "today" and "now" is the user's local time: `users.timezone` (IANA
   name, default `UTC`) through `jiff` with the bundled tz database, so the
   distroless image needs no zoneinfo. Unknown names fall back to UTC.
2. Slot start times are per user (`morning_at` ... `evening_at`, minutes since
   midnight; defaults 07:00, 11:00, 14:00, 19:00), must increase, and the
   evening slot ends at midnight.
3. Completions store the local day they were for (`completions.day`), so a slot
   check survives a time-zone change.
4. The API server's clock is injectable (`AppState::with_clock`), which is how
   the router tests pin "now" to a known instant.

### Auth, as built

1. The backend identifies the caller by `X-Realmdo-User` first, then by
   `Authorization: Bearer rdo_...`. The header wins when both are present: it
   only exists when the gate injected it for a Home session (Caddy strips any
   client copy, and an anonymous-gate admit carries no brokered identity), so it
   is the more trustworthy of the two. Home's widget-loader proxy reaches the
   container directly and stamps the same brokered headers there, next to
   `X-Realm-Widget-PID`, so widgets and pages arrive as their viewer.
   `realm-sdk dev` stamps a synthetic viewer `dev` (admin), the same name the
   Vite proxy uses.
2. The `/public/` URL secret (`pub_` prefix) works like a token: hashed at rest,
   shown once, one per user, regenerate rotates it, delete disables. It grants
   exactly one thing: `GET /public/{secret}/today`, the visible list, nothing
   else. Minting needs a session.
3. Tokens are per user, several per user, named (phone, TRMNL, script), stored
   as a SHA-256 hash, shown once at creation, `last_used_at` written at most
   every 5 minutes. Minting, listing and revoking require a session
   (`via: "session"`); a token gets 403 there, so a leaked device token cannot
   mint more tokens.

### API

```
GET    /api/me                   { username, via: "session" | "token" }
GET    /api/settings             { timezone, slot_times: { morning, before_lunch, afternoon, evening } } (HH:MM)
PUT    /api/settings             same body; validated (IANA zone, HH:MM, increasing)
GET    /api/tasks                open tasks, sorted by urgency desc, with urgency + important
POST   /api/tasks                { title, kind?: "once" | "interval" | "slot", due_on?, after_days?, ramp_days?, slots? } -> 201
                                 once: optional due_on; interval: after_days required, ramp_days optional;
                                 slot: slots required (subset of morning, before_lunch, afternoon, evening)
                                 every task view carries urgency, important, today (slot statuses) and pending_slot
PATCH  /api/tasks/{id}           partial edit within the kind (kind cannot change);
                                 due_on: null clears, ramp_days: null returns to default
POST   /api/tasks/{id}/done      once: archives (idempotent); interval: records a completion, stays open;
                                 slot: optional { slot }, default the most pressing unchecked, no-op when all done
DELETE /api/tasks/{id}           -> 204
GET    /api/public-url           session only; { enabled }
POST   /api/public-url           session only; -> 201 { path } exactly once; rotates
DELETE /api/public-url           session only; disables
GET    /public/{secret}/today    no auth; { date, username, tasks } (visible tasks only)
GET    /api/tokens               session only
POST   /api/tokens               { name } -> 201, carries `secret` exactly once
DELETE /api/tokens/{id}          session only, -> 204
GET    /healthz
```

Errors are `{ "error": "..." }` with 400, 401, 403, 404 or 500; a malformed JSON
body is a 400 in that shape too, not axum's plain-text 422.

### Layout

One repo, three independent toolchains, nothing shared between them. Not a pnpm
workspace, no turbo: `web/` never imports the SDK, and the image must build
without `../holden`.

```
realm.tsx          deployment: services, getTrustedHeaders, gate, info   } Deno project at the root
realm.test.ts      compile-tier tests (realm-sdk test)                   } package.json links the SDK
widgets/           home-screen widgets, one file each                    } deno.json excludes web/ server/
package.json       root scripts fan out to all three toolchains
server/            Rust crate: HTTP API, SQLite, urgency, embeds ../web/dist
web/               React + Vite UI, its own package.json and lockfile
Dockerfile         context = repo root; .dockerignore keeps only server/ and web/
.github/workflows/ci.yml
```

The Android client comes later as `android/`, another independent toolchain
against the JSON API.

Why `realm.tsx` sits at the root: every Realm app has that shape, and
`realm-sdk` commands run from the directory that holds it. Why `web/` has its
own lockfile: a workspace would put the root `package.json`, with its
`link:../holden/...`, inside the Docker build, where that path does not exist.

### Commands

From the root (`pnpm <script>`):

```
typecheck     deno check; tsc in web/; vite build; cargo clippy -D warnings
test          realm-sdk test; vite build; cargo test
fmt / fmt:check   deno fmt (root and web/, markdown included); cargo fmt
validate      realm-sdk validate realm.tsx
build:pack    realm-sdk build --out dist/realmdo.realmpack (pnpm's own `pack` shadows a script of that name)
dev:server    build web/, then cargo run with REALMDO_DATA_DIR=./server/data on :3000
dev:web       vite on :5173, proxies /api to :3000 and injects X-Realmdo-User: dev
              REALMDO_PORT=3100 moves the server port for both, when :3000 is taken
build:image   docker buildx build, both arches, benjick/realmdo:latest
```

`realm-sdk dev` shells out to an `esbuild` binary the SDK does not bring, so the
root `package.json` pins the same `esbuild` holden's root does. It also needs
the image locally: build the host arch with `docker buildx build --load` and tag
it `benjick/realmdo:latest`.

`cargo` needs `web/dist` to exist: `rust-embed` reads `../web/dist` at compile
time (in debug builds it reads from disk at run time, so a rebuilt UI shows
without recompiling). The scripts above build web first; do the same by hand.

Server env: `PORT` (3000), `REALMDO_DATA_DIR` (`/data`). The identity header is
`X-Realmdo-User`, written twice on purpose: `getTrustedHeaders` in `realm.tsx`
and `USER_HEADER` in `server/src/api.rs`.

### Consuming the SDK

`@yourrealm/sdk` is `private: true` (not on npm) and lives at
`../holden/packages/sdk`. Verified 2026-08-28, this works as is:

1. `package.json`: `"@yourrealm/sdk": "link:../holden/packages/sdk"`. pnpm makes
   `node_modules/@yourrealm/sdk` a symlink, links the `realm-sdk` bin, and the
   SDK resolves its own dependencies (`@local/*`, preact, zod) from holden's
   `node_modules`. So holden must be `pnpm install`ed too.
2. `.npmrc`: `@jsr:registry=https://npm.jsr.io`, for `@std/assert` in tests.
3. `deno.json`: `nodeModulesDir: "manual"`, the `compilerOptions` from
   `../holden/deno.json`, and
   `exclude: ["web", "server", "dist",
   "realm-bundle.tsx"]` so `deno check`
   never sees React or Rust.
4. The test harness's compile result carries services and trusted headers but
   not the gate flag; assert `hasAnonymousGate` off `app.evaluate({ config })`
   instead (see `realm.test.ts`).

CI (`.github/workflows/ci.yml`) mirrors this split: the `container` job needs
only this repo; the `realm` job checks out `benjick/holden` as a sibling and
installs it first. If holden is private, set the `HOLDEN_TOKEN` secret.

## Conventions

Borrowed from the Realm repo unless noted.

1. Deno for `realm.tsx`, `widgets/`, and their tests: `deno check`,
   `realm-sdk test`, `realm-sdk validate`, `realm-sdk build`, `realm-sdk dev`.
   Cargo for `server/`, pnpm + Vite for `web/`.
2. Relative imports carry `.ts`/`.tsx` extensions. No `npm:` or `jsr:` prefixes
   in import statements; dependencies live in `package.json`.
3. Short code comments. No backwards-compat shims. No dynamic imports. Don't
   export what nothing imports.
4. Don't commit or push unless asked. Work on `main`, no feature branches.
5. Write in plain language: one idea per sentence, conclusion first, numbered
   lists, no em dashes.

## Open questions

1. **Lists or one list.** The design so far is one ranked list per user. Tags
   may be enough; decide when the UI is real.
2. **Missed slots.** Does a missed before-lunch check roll into the evening, or
   just expire at end of day? Built as: expire (overdue until midnight, then
   gone).
3. **Default ramp** for "every X days" tasks that don't set one. Assumed: the
   same as `after`. Also assumed: a new interval task starts hidden, as if just
   completed. Both to settle against use.
