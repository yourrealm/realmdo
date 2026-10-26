// realmdo: a per-user todo app. One container: the Rust JSON API.
//
// Identity. The gate injects X-Realmdo-User on every request that carries a
// Home session or a Realm native-client token (the Android app), and strips
// any copy the client sent. The backend trusts that header and creates its
// local user row on first sight. TRMNL is admitted on shape alone (/public/
// with a secret in the URL); the backend checks the secret, because the gate
// cannot. Everything else needs a signed-in user.

import { createApp } from "@yourrealm/sdk";

export default createApp({
  name: "realmdo",
  icon: "ph:check-square",
  description:
    "A todo list that creeps up on you: one-off, interval and daily-slot tasks, per user.",

  getTrustedHeaders: (user) => ({
    "X-Realmdo-User": user.username,
  }),

  verifyAnonymousRequest: (request) =>
    new URL(request.url).pathname.startsWith("/public/"),

  getDesiredState: () => ({
    services: {
      api: {
        image: "ghcr.io/yourrealm/realmdo:latest",
        router: {
          containerPort: 3000,
          // No tile: people use the realmdo page, the widget and the phone.
          tile: false,
          // Tighter than the 1000/10s default: the /public/ secret check is
          // the exposed surface, and a human never gets near this.
          rateLimit: { requests: 300, window: "10s" },
          headers: {
            "Content-Security-Policy":
              "default-src 'self'; img-src 'self' data:; frame-ancestors 'self'; base-uri 'self'; form-action 'self'",
          },
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
