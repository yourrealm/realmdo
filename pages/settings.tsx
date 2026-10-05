// realmdo's settings, as a page in Realm: pair a phone, feed TRMNL, set the
// day. Home stamps X-Realmdo-User on every loader and action call, so the
// backend sees a session and allows what it refuses a device token (minting
// tokens and the public URL). The pairing QR and the TRMNL URL need the app's
// own address, which Home hands the page as `services.api`.

import { z } from "@yourrealm/sdk";
import {
  action,
  Button,
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
  type IconName,
  Input,
  Label,
  loader,
  Page,
  type Props,
  QrCode,
} from "@yourrealm/sdk/ui";
import { useEffect, useState } from "@yourrealm/sdk/preact";

export const title = "realmdo";
export const icon: IconName = "CheckCircleIcon";

const token = z.object({
  id: z.number(),
  name: z.string(),
  prefix: z.string(),
  created_at: z.number(),
  last_used_at: z.number().nullable(),
});

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
  tokens: loader({
    ...api,
    outputSchema: z.array(token),
    request: () => ({ method: "GET" as const, path: "/api/tokens" }),
  }),
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
  createToken: action({
    ...api,
    inputSchema: z.object({ name: z.string() }),
    outputSchema: token.extend({ secret: z.string() }),
    request: (input) => ({
      method: "POST" as const,
      path: "/api/tokens",
      body: input,
    }),
  }),
  revokeToken: action({
    ...api,
    inputSchema: z.object({ id: z.number() }),
    request: (input) => ({
      method: "DELETE" as const,
      path: `/api/tokens/${input.id}`,
    }),
  }),
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

function day(ts: number | null): string {
  return ts ? new Date(ts * 1000).toLocaleDateString() : "never";
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

function Tokens({ loaders, actions, services }: Api) {
  const [name, setName] = useState("");
  const [minted, setMinted] = useState<{ name: string; secret: string } | null>(
    null,
  );
  const [confirm, setConfirm] = useState<number | null>(null);
  const [error, setError] = useState<string | null>(null);
  const origin = services.api ?? null;

  const create = async (e: Event) => {
    e.preventDefault();
    const n = name.trim();
    if (!n) return;
    setError(null);
    try {
      const t = await actions.createToken.mutateAsync({ name: n });
      setMinted({ name: t.name, secret: t.secret });
      setName("");
      loaders.tokens.refetch();
    } catch (err) {
      setError(message(err));
    }
  };

  const revoke = async (id: number) => {
    setError(null);
    try {
      await actions.revokeToken.mutateAsync({ id });
      setConfirm(null);
      loaders.tokens.refetch();
    } catch (err) {
      setError(message(err));
    }
  };

  const pair = minted && origin
    ? `realmdo://pair?url=${encodeURIComponent(origin)}&token=${
      encodeURIComponent(minted.secret)
    }`
    : null;

  return (
    <Card>
      <CardHeader>
        <CardTitle>Pair a device</CardTitle>
        <CardDescription>
          Each device gets its own token. Create one, then scan the code with
          the realmdo app, or paste the token and URL into the Mac app. A token
          is shown once; revoke it here if the device is lost.
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-4">
        <form className="flex gap-2" onSubmit={create}>
          <Input
            placeholder="Name: phone, Mac, TRMNL, script"
            aria-label="Token name"
            value={name}
            onValueChange={setName}
          />
          <Button
            type="submit"
            disabled={!name.trim() || actions.createToken.isPending}
          >
            Create
          </Button>
        </form>

        {minted && (
          <div className="flex flex-col gap-4 rounded-xl border border-border p-4 sm:flex-row sm:items-center">
            {pair && <QrCode value={pair} size={176} />}
            <div className="min-w-0 flex-1 space-y-2 text-sm">
              <p>
                Token for <b>{minted.name}</b>. {pair
                  ? "Scan the code with the realmdo app, or copy the token and paste it there with this app's address."
                  : "This app has no address yet, so there is no code to scan. Copy the token now."}
                {" "}
                It will not be shown again.
              </p>
              <Copyable value={minted.secret} label="New token" />
              {origin && <Copyable value={origin} label="Server URL" />}
              <Button
                variant="outline"
                size="sm"
                onClick={() => setMinted(null)}
              >
                Done
              </Button>
            </div>
          </div>
        )}

        {error && <p className="text-sm text-destructive">{error}</p>}

        {loaders.tokens.error && (
          <p className="text-sm text-destructive">
            {loaders.tokens.error.message}
          </p>
        )}
        {loaders.tokens.data && loaders.tokens.data.length === 0 && (
          <p className="text-sm text-muted-foreground">No tokens yet.</p>
        )}
        {loaders.tokens.data && loaders.tokens.data.length > 0 && (
          <ul className="divide-y divide-border">
            {loaders.tokens.data.map((t) => (
              <li key={t.id} className="flex items-center gap-3 py-2 text-sm">
                <div className="min-w-0 flex-1">
                  <p className="font-medium">{t.name}</p>
                  <p className="text-xs text-muted-foreground">
                    {t.prefix}… · created {day(t.created_at)} · last used{" "}
                    {day(t.last_used_at)}
                  </p>
                </div>
                {confirm === t.id
                  ? (
                    <>
                      <Button
                        variant="destructive"
                        size="sm"
                        disabled={actions.revokeToken.isPending}
                        onClick={() => revoke(t.id)}
                      >
                        Revoke {t.name}
                      </Button>
                      <Button
                        variant="ghost"
                        size="sm"
                        onClick={() =>
                          setConfirm(null)}
                      >
                        Keep
                      </Button>
                    </>
                  )
                  : (
                    <Button
                      variant="ghost"
                      size="sm"
                      onClick={() => setConfirm(t.id)}
                    >
                      Revoke
                    </Button>
                  )}
              </li>
            ))}
          </ul>
        )}
      </CardContent>
    </Card>
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
    subtitle="Pair a device, feed TRMNL and set your day."
  >
    <div className="mx-auto max-w-3xl space-y-6 p-6">
      <Tokens {...props} />
      <PublicUrl {...props} />
      <Day {...props} />
    </div>
  </Page>
)) satisfies Props<typeof loaders, typeof actions>;
