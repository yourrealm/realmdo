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
    assertEquals(api.image, "benjick/realmdo:latest");
    assertEquals(api.router!.containerPort, 3000);
    assertEquals(api.router!.rateLimit, { requests: 120, window: "10s" });
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
});

// The harness's compile result carries services and trusted headers, not the
// gate flag, so read it straight off evaluate() as the SDK's own tests do.
Deno.test("declares an anonymous gate (for /api/ and /public/)", () => {
  assertEquals(realmdo.evaluate({ config }).hasAnonymousGate, true);
});
