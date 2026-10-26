// realmdo's settings, as a page in Realm: feed TRMNL, set the day. Home stamps
// X-Realmdo-User on every loader and action call. The TRMNL URL needs the
// app's own address, which Home hands the page as `services.api`.

import { z } from "@yourrealm/sdk";
import {
  action,
  Button,
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
  Input,
  Label,
  loader,
  Page,
  type Props,
  type SurfaceIcon,
} from "@yourrealm/sdk/ui";
import { useEffect, useState } from "@yourrealm/sdk/preact";

export const title = "realmdo";
export const icon: SurfaceIcon = "ph:check-circle";

const SLOTS = ["morning", "before_lunch", "afternoon", "evening"] as const;
type Slot = (typeof SLOTS)[number];
const SLOT_LABEL: Record<Slot, string> = {
  morning: "Morning",
  before_lunch: "Before lunch",
  afternoon: "Afternoon",
  evening: "Evening",
};

const settings = z.object({
  timezone: z.string(),
  slot_times: z.object({
    morning: z.string(),
    before_lunch: z.string(),
    afternoon: z.string(),
    evening: z.string(),
  }),
});
type Settings = z.infer<typeof settings>;

const api = { service: "api", port: 3000 } as const;

export const loaders = {
  publicUrl: loader({
    ...api,
    outputSchema: z.object({ enabled: z.boolean() }),
    request: () => ({ method: "GET" as const, path: "/api/public-url" }),
  }),
  settings: loader({
    ...api,
    outputSchema: settings,
    request: () => ({ method: "GET" as const, path: "/api/settings" }),
  }),
};

export const actions = {
  mintPublicUrl: action({
    ...api,
    inputSchema: z.object({}),
    outputSchema: z.object({ path: z.string() }),
    request: () => ({ method: "POST" as const, path: "/api/public-url" }),
  }),
  disablePublicUrl: action({
    ...api,
    inputSchema: z.object({}),
    request: () => ({ method: "DELETE" as const, path: "/api/public-url" }),
  }),
  saveSettings: action({
    ...api,
    inputSchema: settings,
    outputSchema: settings,
    request: (input) => ({
      method: "PUT" as const,
      path: "/api/settings",
      body: input,
    }),
  }),
};

type Api = Parameters<Props<typeof loaders, typeof actions>>[0];

function message(e: unknown): string {
  return e instanceof Error ? e.message : String(e);
}

/**
 * A secret or URL to copy by hand: the iframe may not write the clipboard,
 * so the field selects all of itself on focus instead.
 */
function Copyable({ value, label }: { value: string; label: string }) {
  return (
    <Input
      readOnly
      aria-label={label}
      value={value}
      onFocus={(e) => (e.currentTarget as HTMLInputElement).select()}
      className="font-mono text-xs"
    />
  );
}

function PublicUrl({ loaders, actions, services }: Api) {
  const [minted, setMinted] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const enabled = loaders.publicUrl.data?.enabled ?? false;
  const origin = services.api ?? null;

  const run = async (f: () => Promise<unknown>) => {
    setError(null);
    try {
      await f();
      loaders.publicUrl.refetch();
    } catch (err) {
      setError(message(err));
    }
  };

  return (
    <Card>
      <CardHeader>
        <CardTitle>TRMNL</CardTitle>
        <CardDescription>
          A read-only feed of today's list for TRMNL's polling plugin. Anyone
          with the URL can read the titles, never the notes or steps. It is
          shown once; create a new one to rotate it.
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-3">
        <div className="flex items-center gap-2">
          <Button
            disabled={actions.mintPublicUrl.isPending || !origin}
            onClick={() =>
              run(async () => {
                const { path } = await actions.mintPublicUrl.mutateAsync({});
                setMinted(origin + path);
              })}
          >
            {enabled ? "Create a new URL" : "Create URL"}
          </Button>
          {enabled && (
            <Button
              variant="ghost"
              disabled={actions.disablePublicUrl.isPending}
              onClick={() =>
                run(async () => {
                  await actions.disablePublicUrl.mutateAsync({});
                  setMinted(null);
                })}
            >
              Turn off
            </Button>
          )}
          <span className="text-sm text-muted-foreground">
            {enabled ? "On" : "Off"}
          </span>
        </div>
        {!origin && (
          <p className="text-sm text-muted-foreground">
            This app has no address yet, so there is no URL to give out.
          </p>
        )}
        {minted && <Copyable value={minted} label="TRMNL URL" />}
        {error && <p className="text-sm text-destructive">{error}</p>}
      </CardContent>
    </Card>
  );
}

function Day({ loaders, actions }: Api) {
  const [draft, setDraft] = useState<Settings | null>(null);
  const [status, setStatus] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const browserZone = Intl.DateTimeFormat().resolvedOptions().timeZone;

  // Seeded once from the server; the form owns it after that.
  useEffect(() => {
    if (!draft && loaders.settings.data) setDraft(loaders.settings.data);
  }, [loaders.settings.data]);

  if (!draft) {
    return loaders.settings.error
      ? (
        <p className="text-sm text-destructive">
          {loaders.settings.error.message}
        </p>
      )
      : null;
  }

  const save = async (e: Event) => {
    e.preventDefault();
    setError(null);
    setStatus(null);
    try {
      setDraft(await actions.saveSettings.mutateAsync(draft));
      setStatus("Saved");
      loaders.settings.refetch();
    } catch (err) {
      setError(message(err));
    }
  };

  return (
    <Card>
      <CardHeader>
        <CardTitle>Your day</CardTitle>
        <CardDescription>
          Days, due dates and daily slots follow this time zone. Each slot
          starts at its time and runs until the next; evening runs to midnight.
        </CardDescription>
      </CardHeader>
      <CardContent>
        <form className="space-y-4" onSubmit={save}>
          <div className="space-y-1.5">
            <Label htmlFor="tz">Time zone</Label>
            <div className="flex gap-2">
              <Input
                id="tz"
                value={draft.timezone}
                onValueChange={(v) => setDraft({ ...draft, timezone: v })}
              />
              {draft.timezone !== browserZone && (
                <Button
                  type="button"
                  variant="outline"
                  onClick={() => setDraft({ ...draft, timezone: browserZone })}
                >
                  Use {browserZone}
                </Button>
              )}
            </div>
          </div>
          <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
            {SLOTS.map((slot) => (
              <div key={slot} className="space-y-1.5">
                <Label htmlFor={slot}>{SLOT_LABEL[slot]}</Label>
                <Input
                  id={slot}
                  type="time"
                  required
                  value={draft.slot_times[slot]}
                  onValueChange={(v) =>
                    setDraft({
                      ...draft,
                      slot_times: { ...draft.slot_times, [slot]: v },
                    })}
                />
              </div>
            ))}
          </div>
          <div className="flex items-center gap-3">
            <Button type="submit" disabled={actions.saveSettings.isPending}>
              Save
            </Button>
            {status && (
              <span className="text-sm text-muted-foreground">{status}</span>
            )}
            {error && <span className="text-sm text-destructive">{error}</span>}
          </div>
        </form>
      </CardContent>
    </Card>
  );
}

export default ((props) => (
  <Page
    title="Settings"
    subtitle="Feed TRMNL and set your day."
  >
    <div className="mx-auto max-w-3xl space-y-6 p-6">
      <PublicUrl {...props} />
      <Day {...props} />
    </div>
  </Page>
)) satisfies Props<typeof loaders, typeof actions>;
