import {
  type FormEvent,
  useCallback,
  useEffect,
  useRef,
  useState,
} from "react";
import {
  api,
  type Kind,
  type Slot,
  SLOT_LABEL,
  SLOTS,
  type Task,
} from "./api.ts";
import { Settings } from "./Settings.tsx";
import { TaskEditor } from "./Editor.tsx";
import { Done } from "./Done.tsx";
import { StepList } from "./Steps.tsx";

const DAY_MS = 86_400_000;

/// "today", "tomorrow", "in 3 d", "2 d ago". Days are UTC, like the server.
function dueLabel(dueOn: string): string {
  const [y, m, d] = dueOn.split("-").map(Number);
  const due = Date.UTC(y ?? 0, (m ?? 1) - 1, d ?? 1) / DAY_MS;
  const today = Math.floor(Date.now() / DAY_MS);
  const diff = due - today;
  if (diff === 0) return "today";
  if (diff === 1) return "tomorrow";
  if (diff === -1) return "yesterday";
  return diff > 0 ? `in ${diff} d` : `${-diff} d ago`;
}

/// "every 6 d · done 3 d ago" for interval tasks, the due label for one-offs.
function detail(t: Task): string | null {
  if (t.kind === "interval") {
    const every = `every ${t.after_days ?? 0} d`;
    if (t.last_done_at === null) return every;
    const days = Math.floor(Date.now() / DAY_MS) -
      Math.floor((t.last_done_at * 1000) / DAY_MS);
    return `${every} · done ${days === 0 ? "today" : `${days} d ago`}`;
  }
  return t.due_on ? dueLabel(t.due_on) : null;
}

/// Notes as text, with bare http(s) URLs made clickable.
function Notes({ text }: { text: string }) {
  return (
    <p className="notes">
      {text.split(/(https?:\/\/\S+)/).map((part, i) =>
        i % 2
          ? (
            <a key={i} href={part} target="_blank" rel="noreferrer">
              {part}
            </a>
          )
          : part
      )}
    </p>
  );
}

function NotesToggle(
  { open, onClick }: { open: boolean; onClick: () => void },
) {
  return (
    <button
      className={`link notes-toggle${open ? " open" : ""}`}
      aria-label={open ? "Hide notes" : "Show notes"}
      aria-expanded={open}
      title="Notes and steps"
      onClick={onClick}
    >
      ≡
    </button>
  );
}

/// What the ≡ toggle opens: the notes, then the steps.
function Expanded({ task }: { task: Task }) {
  return (
    <>
      {task.notes && <Notes text={task.notes} />}
      {task.steps && <StepList steps={task.steps} />}
    </>
  );
}

export function App() {
  const [username, setUsername] = useState<string | null>(null);
  const [tasks, setTasks] = useState<Task[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [title, setTitle] = useState("");
  const [dueOn, setDueOn] = useState("");
  const [kind, setKind] = useState<Kind>("once");
  const [after, setAfter] = useState("");
  const [ramp, setRamp] = useState("");
  const [slots, setSlots] = useState<Slot[]>(["morning"]);
  const [showLater, setShowLater] = useState(false);
  const [selected, setSelected] = useState(0);
  const [editing, setEditing] = useState<number | null>(null);
  const [open, setOpen] = useState<number | null>(null);
  const [view, setView] = useState<"tasks" | "done" | "settings">("tasks");
  const input = useRef<HTMLInputElement>(null);

  const onError = useCallback((e: unknown) => {
    setError(e instanceof Error ? e.message : String(e));
  }, []);

  const run = useCallback(async (action: () => Promise<unknown>) => {
    try {
      await action();
      setTasks(await api.tasks());
      setError(null);
    } catch (e) {
      onError(e);
    }
  }, [onError]);

  useEffect(() => {
    void run(async () => setUsername((await api.me()).username));
  }, [run]);

  // Slot tasks live in the today strip; the ranked list is everything else.
  const daily = tasks.filter((t) => t.kind === "slot");
  const ranked = tasks.filter((t) => t.kind !== "slot");
  const later = ranked.filter((t) => t.urgency < 0);
  const shown = showLater ? ranked : ranked.filter((t) => t.urgency >= 0);
  // One keyboard selection runs down the strip, then the ranked list.
  const rows = [...daily, ...shown];
  const sel = Math.min(selected, Math.max(rows.length - 1, 0));

  function add(e: FormEvent) {
    e.preventDefault();
    if (!title.trim()) return;
    const task = kind === "once"
      ? { title, kind, due_on: dueOn || null }
      : kind === "slot"
      ? { title, kind, slots }
      : {
        title,
        kind,
        after_days: Number(after || 0),
        ...(ramp ? { ramp_days: Number(ramp) } : {}),
      };
    void run(async () => {
      await api.create(task);
      setTitle("");
      setDueOn("");
      setAfter("");
      setRamp("");
    });
  }

  const refresh = useCallback(() => void run(async () => {}), [run]);

  const saved = useCallback(() => {
    setEditing(null);
    void run(async () => {});
  }, [run]);

  const remove = useCallback((id: number) => {
    setEditing(null);
    void run(() => api.remove(id));
  }, [run]);

  useEffect(() => {
    function onKey(e: KeyboardEvent) {
      if (e.target instanceof HTMLInputElement) {
        if (e.key === "Escape") e.target.blur();
        return;
      }
      if (view !== "tasks") {
        if (e.key === "Escape") setView("tasks");
        return;
      }
      if (editing !== null) return;
      const task = rows[sel];
      switch (e.key) {
        case "j":
          setSelected(Math.min(sel + 1, rows.length - 1));
          break;
        case "k":
          setSelected(Math.max(sel - 1, 0));
          break;
        case "x":
          if (task) void run(() => api.done(task.id));
          break;
        case "d":
          if (task) remove(task.id);
          break;
        case "e":
          if (task) setEditing(task.id);
          break;
        case "o":
          if (task?.notes || task?.steps) {
            setOpen(open === task.id ? null : task.id);
          }
          break;
        case "n":
        case "/":
          e.preventDefault();
          input.current?.focus();
          break;
      }
    }
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [rows, sel, run, remove, view, editing, open]);

  return (
    <main>
      <header>
        <h1>
          <picture>
            <source
              srcSet="/logo-dark.svg"
              media="(prefers-color-scheme: dark)"
            />
            <img className="mark" src="/logo.svg" alt="" />
          </picture>
          realmdo
        </h1>
        <nav>
          <span className="user">{username ?? "…"}</span>
          {view === "tasks"
            ? (
              <>
                <button className="link" onClick={() => setView("done")}>
                  done
                </button>
                <button className="link" onClick={() => setView("settings")}>
                  settings
                </button>
              </>
            )
            : (
              <button className="link" onClick={() => setView("tasks")}>
                tasks
              </button>
            )}
        </nav>
      </header>

      {error && <p className="error">{error}</p>}

      {view === "settings" && <Settings onError={onError} />}
      {view === "done" && <Done onChanged={refresh} onError={onError} />}
      {view === "tasks" && (
        <>
          <form onSubmit={add}>
            <input
              ref={input}
              autoFocus
              placeholder="New task"
              value={title}
              onChange={(e) => setTitle(e.target.value)}
            />
            <select
              value={kind}
              onChange={(e) => setKind(e.target.value as Kind)}
            >
              <option value="once">once</option>
              <option value="interval">every</option>
              <option value="slot">daily</option>
            </select>
            {kind === "once" && (
              <input
                type="date"
                value={dueOn}
                onChange={(e) => setDueOn(e.target.value)}
              />
            )}
            {kind === "interval" && (
              <>
                <input
                  type="number"
                  min={0}
                  required
                  placeholder="days"
                  title="Days hidden after completion"
                  value={after}
                  onChange={(e) => setAfter(e.target.value)}
                />
                <input
                  type="number"
                  min={1}
                  placeholder="ramp"
                  title="Days from appearing to important (default: same as days)"
                  value={ramp}
                  onChange={(e) => setRamp(e.target.value)}
                />
              </>
            )}
            {kind === "slot" && (
              <div className="slots">
                {SLOTS.map((slot) => (
                  <label key={slot} className="slot-pick">
                    <input
                      type="checkbox"
                      checked={slots.includes(slot)}
                      onChange={(e) =>
                        setSlots(
                          e.target.checked
                            ? SLOTS.filter((s) =>
                              s === slot || slots.includes(s)
                            )
                            : slots.filter((s) => s !== slot),
                        )}
                    />
                    {SLOT_LABEL[slot]}
                  </label>
                ))}
              </div>
            )}
            <button type="submit">Add</button>
          </form>

          {daily.length > 0 && (
            <ul className="strip">
              {daily.map((t, i) => (
                <li
                  key={t.id}
                  className={[
                    t.important ? "important" : "",
                    i === sel ? "selected" : "",
                  ].join(" ")}
                  onClick={() => setSelected(i)}
                >
                  {editing === t.id
                    ? (
                      <TaskEditor
                        task={t}
                        onSaved={saved}
                        onCancel={() => setEditing(null)}
                        onDelete={() => remove(t.id)}
                        onError={onError}
                      />
                    )
                    : (
                      <>
                        <span
                          className="title"
                          onClick={() => setEditing(t.id)}
                        >
                          {t.title}
                        </span>
                        {(t.notes || t.steps) && (
                          <NotesToggle
                            open={open === t.id}
                            onClick={() => setOpen(open === t.id ? null : t.id)}
                          />
                        )}
                        {(t.today ?? []).map(({ slot, state }) => (
                          <button
                            key={slot}
                            className={`chip ${state}`}
                            disabled={state === "done"}
                            title={state}
                            onClick={() => run(() => api.done(t.id, slot))}
                          >
                            {state === "done" ? "✓ " : ""}
                            {SLOT_LABEL[slot]}
                          </button>
                        ))}
                        {open === t.id && <Expanded task={t} />}
                      </>
                    )}
                </li>
              ))}
            </ul>
          )}

          <ul>
            {shown.map((t, i) => (
              <li
                key={t.id}
                className={[
                  t.important ? "important" : "",
                  daily.length + i === sel ? "selected" : "",
                  t.urgency < 0 ? "later" : "",
                ].join(" ")}
                onClick={() => setSelected(daily.length + i)}
              >
                {editing === t.id
                  ? (
                    <TaskEditor
                      task={t}
                      onSaved={saved}
                      onCancel={() => setEditing(null)}
                      onDelete={() => remove(t.id)}
                      onError={onError}
                    />
                  )
                  : (
                    <>
                      <button
                        className="check"
                        aria-label="Done"
                        onClick={() => run(() => api.done(t.id))}
                      >
                        ○
                      </button>
                      <span className="title" onClick={() => setEditing(t.id)}>
                        {t.kind === "interval" && (
                          <span className="kind">↻</span>
                        )}
                        {t.title}
                      </span>
                      {detail(t) && <span className="due">{detail(t)}</span>}
                      {(t.notes || t.steps) && (
                        <NotesToggle
                          open={open === t.id}
                          onClick={() => setOpen(open === t.id ? null : t.id)}
                        />
                      )}
                      {open === t.id && <Expanded task={t} />}
                    </>
                  )}
              </li>
            ))}
          </ul>

          {tasks.length === 0 && <p className="empty">Nothing to do.</p>}
          {later.length > 0 && (
            <button
              className="link"
              onClick={() => setShowLater(!showLater)}
            >
              {showLater ? "hide" : "show"} {later.length} later
            </button>
          )}
        </>
      )}

      <footer>
        j/k move · x done · e edit · o notes · d delete · n new · esc back
      </footer>
    </main>
  );
}
