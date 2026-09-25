import type { Step } from "./api.ts";

/** 90 -> "1:30". */
export function formatDuration(seconds: number): string {
  const m = Math.floor(seconds / 60);
  const s = seconds % 60;
  return `${m}:${String(s).padStart(2, "0")}`;
}

/** "1:30" -> 90, "45" -> 45 (seconds). Null when it is neither. */
export function parseDuration(text: string): number | null {
  const t = text.trim();
  const mss = /^(\d+):([0-5]\d)$/.exec(t);
  if (mss) return Number(mss[1]) * 60 + Number(mss[2]);
  return /^\d+$/.test(t) ? Number(t) : null;
}

/** A step while it is edited: the duration is still text. */
export interface StepDraft {
  title: string;
  speech: string;
  details: string;
  duration: string;
}

export function toDrafts(steps: Step[] | null): StepDraft[] {
  return (steps ?? []).map((s) => ({
    title: s.title,
    speech: s.speech ?? "",
    details: s.details ?? "",
    duration: formatDuration(s.seconds),
  }));
}

/** The steps to save, or an error naming the first bad row. */
export function fromDrafts(drafts: StepDraft[]): Step[] | string {
  const steps: Step[] = [];
  for (const [i, d] of drafts.entries()) {
    const seconds = parseDuration(d.duration);
    if (!d.title.trim()) return `step ${i + 1} needs a title`;
    if (!seconds) return `step ${i + 1}: duration as m:ss or seconds`;
    steps.push({
      title: d.title,
      speech: d.speech || null,
      details: d.details || null,
      seconds,
    });
  }
  return steps;
}

/** Editor rows for a routine's steps. */
export function StepsEditor(
  { drafts, onChange }: {
    drafts: StepDraft[];
    onChange: (drafts: StepDraft[]) => void;
  },
) {
  const set = (i: number, patch: Partial<StepDraft>) =>
    onChange(drafts.map((d, j) => j === i ? { ...d, ...patch } : d));
  const move = (i: number, by: number) => {
    const next = [...drafts];
    const [d] = next.splice(i, 1);
    if (d) next.splice(i + by, 0, d);
    onChange(next);
  };
  const total = drafts.reduce(
    (sum, d) => sum + (parseDuration(d.duration) ?? 0),
    0,
  );
  return (
    <div className="steps-editor">
      {drafts.map((d, i) => (
        <fieldset key={i} className="step">
          <div className="step-row">
            <input
              placeholder={`Step ${i + 1}`}
              value={d.title}
              onChange={(e) => set(i, { title: e.target.value })}
            />
            <input
              className="duration"
              placeholder="m:ss"
              title="Duration: m:ss, or plain seconds"
              value={d.duration}
              onChange={(e) => set(i, { duration: e.target.value })}
              onBlur={() => {
                const s = parseDuration(d.duration);
                if (s) set(i, { duration: formatDuration(s) });
              }}
            />
            <button
              type="button"
              className="link"
              aria-label="Move up"
              disabled={i === 0}
              onClick={() => move(i, -1)}
            >
              ↑
            </button>
            <button
              type="button"
              className="link"
              aria-label="Move down"
              disabled={i === drafts.length - 1}
              onClick={() => move(i, 1)}
            >
              ↓
            </button>
            <button
              type="button"
              className="link danger"
              aria-label="Remove step"
              onClick={() => onChange(drafts.filter((_, j) => j !== i))}
            >
              ×
            </button>
          </div>
          <input
            placeholder="Spoken (empty: the title)"
            value={d.speech}
            onChange={(e) => set(i, { speech: e.target.value })}
          />
          <textarea
            placeholder="Details to read"
            rows={Math.min(Math.max(d.details.split("\n").length, 1), 8)}
            value={d.details}
            onChange={(e) => set(i, { details: e.target.value })}
          />
        </fieldset>
      ))}
      <div className="step-row">
        <button
          type="button"
          className="link"
          onClick={() =>
            onChange([
              ...drafts,
              { title: "", speech: "", details: "", duration: "1:00" },
            ])}
        >
          + step
        </button>
        {drafts.length > 0 && (
          <span className="due">total {formatDuration(total)}</span>
        )}
      </div>
    </div>
  );
}

/** A routine's steps, read-only, under a task row. */
export function StepList({ steps }: { steps: Step[] }) {
  return (
    <ol className="steps">
      {steps.map((s, i) => (
        <li key={i}>
          <span>{s.title}</span>
          <span className="due">{formatDuration(s.seconds)}</span>
          {s.details && <p className="notes">{s.details}</p>}
        </li>
      ))}
    </ol>
  );
}
