import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// Dev: the Rust server (REALMDO_PORT, default 3000) expects the brokered
// identity header, which the Realm gate injects in production. Here the
// proxy plays the gate.
const apiPort = process.env.REALMDO_PORT ?? "3000";

export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      "/api": {
        target: `http://127.0.0.1:${apiPort}`,
        headers: { "X-Realmdo-User": "dev" },
      },
    },
  },
});
