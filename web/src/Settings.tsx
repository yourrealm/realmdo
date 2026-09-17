import { type FormEvent, useCallback, useEffect, useState } from "react";
import { QRCodeSVG } from "qrcode.react";
import {
  api,
  type Settings as SettingsData,
  SLOT_LABEL,
  SLOTS,
  type Token,
} from "./api.ts";

/// What the Android app's scanner expects: this origin plus the fresh token.
function pairUrl(secret: string): string {
  const url = encodeURIComponent(location.origin);
  return `realmdo://pair?url=${url}&token=${encodeURIComponent(secret)}`;
}

function when(ts: number | null): string {
  return ts ? new Date(ts * 1000).toLocaleDateString() : "never";
}

function TimeSettings({ onError }: { onError: (e: unknown) => void }) {
  const [settings, setSettings] = useState<SettingsData | null>(null);
  const [saved, setSaved] = useState(false);
  const browserZone = Intl.DateTimeFormat().resolvedOptions().timeZone;

  useEffect(() => {
    api.settings().then(setSettings).catch(onError);
  }, [onError]);

  if (!settings) return null;

  function save(e: FormEvent) {
    e.preventDefault();
    if (!settings) return;
    api
      .saveSettings(settings)
      .then((s) => {
        setSettings(s);
        setSaved(true);
      })
      .catch(onError);
  }

  return (
    <section>
      <h2>Time</h2>
      <p className="hint">
        Days, due dates and slots follow this zone. Slot times are when each
        slot starts.
      </p>
      <form className="settings" onSubmit={save}>
        <label>
          time zone
          <input
            value={settings.timezone}
            onChange={(e) =>
              setSettings({ ...settings, timezone: e.target.value })}
          />
          {settings.timezone !== browserZone && (
            <button
              type="button"
              className="link"
              onClick={() =>
                setSettings({ ...settings, timezone: browserZone })}
            >
              use {browserZone}
            </button>
          )}
        </label>
        {SLOTS.map((slot) => (
          <label key={slot}>
            {SLOT_LABEL[slot]}
            <input
              type="time"
              required
              value={settings.slot_times[slot]}
              onChange={(e) =>
                setSettings({
                  ...settings,
                  slot_times: {
                    ...settings.slot_times,
                    [slot]: e.target.value,
                  },
                })}
            />
          </label>
        ))}
        <p>
          <button type="submit">Save</button>{" "}
          {saved && <span className="hint">saved</span>}
        </p>
      </form>
    </section>
  );
}

function PublicUrl({ onError }: { onError: (e: unknown) => void }) {
  const [enabled, setEnabled] = useState(false);
  const [minted, setMinted] = useState<string | null>(null);

  useEffect(() => {
    api.publicUrl().then((s) => setEnabled(s.enabled)).catch(onError);
  }, [onError]);

  function mint() {
    api
      .mintPublicUrl()
      .then((m) => {
        setMinted(location.origin + m.path);
        setEnabled(true);
      })
      .catch(onError);
  }

  return (
    <section>
      <h2>Public today URL</h2>
      <p className="hint">
        A read-only JSON feed of today's list, for callers that cannot send
        headers (TRMNL). Anyone with the URL can read it. Shown once; regenerate
        to rotate.
      </p>
      <p>
        <button onClick={mint}>{enabled ? "Regenerate" : "Create"}</button>{" "}
        {enabled && (
          <button
            className="link"
            onClick={() =>
              api.disablePublicUrl().then(() => {
                setEnabled(false);
                setMinted(null);
              }, onError)}
          >
            disable
          </button>
        )}
      </p>
      {minted && (
        <div className="minted">
          <code>{minted}</code>
          <p>
            <button
              onClick={() =>
                navigator.clipboard.writeText(minted).catch(onError)}
            >
              Copy
            </button>
          </p>
        </div>
      )}
    </section>
  );
}

export function Settings({ onError }: { onError: (e: unknown) => void }) {
  const [tokens, setTokens] = useState<Token[]>([]);
  const [name, setName] = useState("");
  const [minted, setMinted] = useState<{ name: string; secret: string } | null>(
    null,
  );

  const load = useCallback(() => api.tokens().then(setTokens).catch(onError), [
    onError,
  ]);
  useEffect(() => {
    void load();
  }, [load]);

  function create(e: FormEvent) {
    e.preventDefault();
    if (!name.trim()) return;
    api
      .createToken(name)
      .then((t) => {
        setMinted({ name: t.name, secret: t.secret });
        setName("");
        return load();
      })
      .catch(onError);
  }

  return (
    <>
      <TimeSettings onError={onError} />
      <PublicUrl onError={onError} />
      <section>
        <h2>API tokens</h2>
        <p className="hint">
          Send one as <code>Authorization: Bearer …</code> to{" "}
          <code>/api/</code>. A token is shown once, at creation.
        </p>

        <form onSubmit={create}>
          <input
            autoFocus
            placeholder="Name (phone, TRMNL, script)"
            value={name}
            onChange={(e) => setName(e.target.value)}
          />
          <button type="submit">Create</button>
        </form>

        {minted && (
          <div className="minted">
            <p>
              Token for{" "}
              <b>{minted.name}</b>. Scan it with the Android app, or copy it
              now; it will not be shown again.
            </p>
            <QRCodeSVG
              className="qr"
              value={pairUrl(minted.secret)}
              size={192}
              marginSize={2}
              title="Pairing code for the Android app"
            />
            <code>{minted.secret}</code>
            <p>
              <button
                onClick={() =>
                  navigator.clipboard.writeText(minted.secret).catch(onError)}
              >
                Copy
              </button>{" "}
              <button onClick={() => setMinted(null)}>Done</button>
            </p>
          </div>
        )}

        <ul>
          {tokens.map((t) => (
            <li key={t.id}>
              <span className="title">{t.name}</span>
              <span className="due">
                {t.prefix}… · created {when(t.created_at)} · last used{" "}
                {when(t.last_used_at)}
              </span>
              <button
                className="link"
                onClick={() => api.revokeToken(t.id).then(load, onError)}
              >
                revoke
              </button>
            </li>
          ))}
        </ul>
        {tokens.length === 0 && <p className="empty">No tokens.</p>}
      </section>
    </>
  );
}
