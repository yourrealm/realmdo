import { assert, assertEquals } from "@std/assert";
import { type ConfigOf, type GrantsOf, Realm } from "@yourrealm/sdk/test";
import realmdo from "./realm.tsx";

const config: ConfigOf<typeof realmdo> = {};
const grants: GrantsOf<typeof realmdo> = {};

Realm.describe(realmdo, import.meta.resolve("./realm.tsx"), ({ test }) => {
  test("brokers the Home username as X-Realmdo-User", async (t) => {
    const result = await t.compile({ config, grants });
    assert(
      !result.error,
      `expected success, got: ${JSON.stringify(result, null, 2)}`,
    );
    const trusted = result.app.trustedHeaders!;
    assertEquals(trusted.keys, ["X-Realmdo-User"]);
    assertEquals(trusted.byRole.admin!["X-Realmdo-User"], {
      field: "username",
    });
    assertEquals(trusted.byRole.member!["X-Realmdo-User"], {
      field: "username",
    });
  });

  test("api service: image, sqlite backup, exec-form healthcheck, tight rate limit", async (t) => {
    const result = await t.compile({ config, grants });
    assert(!result.error);
    const api = result.app.services.api!;
    assertEquals(api.image, "ghcr.io/yourrealm/realmdo:latest");
    assertEquals(api.router!.containerPort, 3000);
    assertEquals(api.router!.rateLimit, { requests: 300, window: "10s" });
    assert(
      api.router!.headers!["Content-Security-Policy"]!.startsWith(
        "default-src 'self'",
      ),
    );
    assertEquals(api.volumes![0]!.backup, {
      kind: "sqlite",
      file: "realmdo.db",
    });
    // No shell in the distroless runtime: CMD, never CMD-SHELL.
    assertEquals(api.healthcheck!.test[0], "CMD");
  });

  test("the tasks widget reaches the api service as the viewer", async (t) => {
    const result = await t.compile({ config, grants });
    assert(!result.error);
    const widget = result.app.ui!.widgets!.tasks!;
    assertEquals(widget.title, "Tasks");
    assertEquals(widget.loaders!.tasks!.service, "api");
    assertEquals(widget.loaders!.tasks!.port, 3000);
    assertEquals(Object.keys(widget.actions!).sort(), ["add", "done"]);
    // Identity comes from getTrustedHeaders, stamped by Home on every loader
    // and action call. Nothing here may declare it: a colliding name would be
    // dropped, and declaring it would suggest the widget can pick a user.
    for (
      const call of [
        ...Object.values(widget.loaders!),
        ...Object.values(widget.actions!),
      ]
    ) {
      assertEquals(call.headers, undefined);
    }
  });

  test("the settings page reaches the api service as the viewer", async (t) => {
    const result = await t.compile({ config, grants });
    assert(!result.error);
    const page = result.app.ui!.pages!.settings!;
    assertEquals(page.title, "realmdo");
    assertEquals(Object.keys(page.loaders!).sort(), [
      "publicUrl",
      "settings",
    ]);
    assertEquals(Object.keys(page.actions!).sort(), [
      "disablePublicUrl",
      "mintPublicUrl",
      "saveSettings",
    ]);
    // Identity is the brokered header; a page must never declare it (see the
    // widget test above).
    for (
      const call of [
        ...Object.values(page.loaders!),
        ...Object.values(page.actions!),
      ]
    ) {
      assertEquals(call.service, "api");
      assertEquals(call.headers, undefined);
    }
  });
});

// The harness's compile result carries services and trusted headers, not the
// gate flag, so read it straight off evaluate() as the SDK's own tests do.
Deno.test("declares an anonymous gate", () => {
  assertEquals(realmdo.evaluate({ config }).hasAnonymousGate, true);
});

Deno.test("the anonymous gate admits /public/ only", () => {
  const admits = (path: string, headers: HeadersInit = {}) =>
    realmdo.verifyAnonymousRequest!(
      new Request(`https://realmdo.example${path}`, { headers }),
      () => "",
    );
  assertEquals(admits("/public/pub_abc/today"), true);
  // The phone signs in through Realm now, so a bearer alone is not enough.
  assertEquals(admits("/api/tasks", { Authorization: "Bearer rdo_x" }), false);
  assertEquals(admits("/api/tasks"), false);
  assertEquals(admits("/"), false);
});
