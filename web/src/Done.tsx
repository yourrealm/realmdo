import { useCallback, useEffect, useState } from "react";
import { api, type Completion, SLOT_LABEL } from "./api.ts";

const DAY_MS = 86_400_000;

/// "today", "yesterday" or a short date, in the browser's time zone.
function dayLabel(date: Date): string {
  const start = (d: Date) =>
    new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime();
  const diff = Math.round((start(new Date()) - start(date)) / DAY_MS);
  if (diff === 0) return "Today";
  if (diff === 1) return "Yesterday";
  return date.toLocaleDateString(undefined, {
    weekday: "short",
    day: "numeric",
    month: "short",
  });
}

/// What was checked off, newest first: the latest check of every recurring
/// task and the latest archived one-offs. Restore takes a check back.
export function Done(
  { onChanged, onError }: {
    onChanged: () => void;
    onError: (e: unknown) => void;
  },
) {
  const [items, setItems] = useState<Completion[] | null>(null);

  const load = useCallback(() => {
    api.completions().then(setItems).catch(onError);
  }, [onError]);

  useEffect(load, [load]);

  function restore(id: number) {
    api.restore(id)
      .then(() => {
        load();
        onChanged();
      })
      .catch(onError);
  }

  if (!items) return null;
  if (items.length === 0) return <p className="empty">Nothing done yet.</p>;

  const groups: [string, Completion[]][] = [];
  for (const c of items) {
    const label = dayLabel(new Date(c.completed_at * 1000));
    const last = groups.at(-1);
    if (last && last[0] === label) last[1].push(c);
    else groups.push([label, [c]]);
  }

  return (
    <>
      {groups.map(([label, list]) => (
        <section key={label}>
          <h2>{label}</h2>
          <ul>
            {list.map((c) => (
              <li key={c.id}>
                <span className="title">
                  {c.kind === "interval" && <span className="kind">↻</span>}
                  {c.title}
                  {c.slot && <span className="due">{SLOT_LABEL[c.slot]}</span>}
                </span>
                <span className="due">
                  {new Date(c.completed_at * 1000).toLocaleTimeString([], {
                    hour: "2-digit",
                    minute: "2-digit",
                  })}
                </span>
                <button className="link" onClick={() => restore(c.id)}>
                  restore
                </button>
              </li>
            ))}
          </ul>
        </section>
      ))}
    </>
  );
}
