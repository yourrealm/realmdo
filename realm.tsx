// realmdo: a per-user todo app. One container: Rust API + embedded web UI.
//
// Identity. The gate injects X-Realmdo-User on every request that carries a
// Home session, and strips any copy the client sent. The backend trusts that
// header and creates its local user row on first sight. Machine callers are
// admitted on shape alone (/api/ with a bearer header, /public/ with a secret
// in the URL); the backend verifies the credential, because the gate cannot
// (it sees only method, URL and headers). Everything else needs a Home
// session.

import { createApp } from "@yourrealm/sdk";

export default createApp({
  name: "realmdo",
  description:
    "A todo list that creeps up on you: one-off, interval and daily-slot tasks, per user.",

  getTrustedHeaders: (user) => ({
    "X-Realmdo-User": user.username,
  }),

  verifyAnonymousRequest: (request) => {
    const { pathname } = new URL(request.url);
    if (pathname.startsWith("/api/")) {
      return request.headers.get("Authorization")?.startsWith("Bearer ") ??
        false;
    }
    return pathname.startsWith("/public/");
  },

  info: ({ services }) => ({
    apiUrl: {
      label: "API base URL",
      value: services.api ? `${services.api}/api` : null,
      description:
        "Create a token on realmdo's Settings page and send it as Authorization: Bearer <token>.",
    },
  }),

  getDesiredState: () => ({
    services: {
      api: {
        image: "benjick/realmdo:latest",
        router: {
          containerPort: 3000,
          // Tighter than the 1000/10s default: the bearer-token check on /api/
          // is the exposed surface, and a human on the UI never gets near this.
          rateLimit: { requests: 120, window: "10s" },
        },
        volumes: [
          {
            hostPath: "./data",
            containerPath: "/data",
            backup: { kind: "sqlite", file: "realmdo.db" },
          },
        ],
        // Exec form: the distroless runtime has no shell, so the binary
        // self-checks via its `healthcheck` arg.
        healthcheck: {
          test: ["CMD", "/app/realmdo", "healthcheck"],
          interval: 5,
          timeout: 3,
          retries: 12,
          startPeriod: 2,
        },
      },
    },
  }),
});
