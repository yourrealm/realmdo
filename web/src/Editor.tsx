import { type FormEvent, useState } from "react";
import {
  api,
  type Slot,
  SLOT_LABEL,
  SLOTS,
  type Task,
  type TaskPatch,
} from "./api.ts";

/** Inline editor for one task. Fields follow the kind; the kind is fixed. */
export function TaskEditor(
  { task, onSaved, onCancel, onError }: {
    task: Task;
    onSaved: () => void;
    onCancel: () => void;
    onError: (e: unknown) => void;
  },
) {
  const [title, setTitle] = useState(task.title);
  const [dueOn, setDueOn] = useState(task.due_on ?? "");
  const [after, setAfter] = useState(String(task.after_days ?? 0));
  const [ramp, setRamp] = useState(
    task.ramp_days === null ? "" : String(task.ramp_days),
  );
  const [slots, setSlots] = useState<Slot[]>(task.slots ?? []);

  function submit(e: FormEvent) {
    e.preventDefault();
    if (!title.trim()) return;
    const patch: TaskPatch = { title };
    if (task.kind === "once") patch.due_on = dueOn || null;
    if (task.kind === "interval") {
      patch.after_days = Number(after || 0);
      patch.ramp_days = ramp ? Number(ramp) : null;
    }
    if (task.kind === "slot") patch.slots = slots;
    api.update(task.id, patch).then(onSaved).catch(onError);
  }

  return (
    <form
      className="editor"
      onSubmit={submit}
      onKeyDown={(e) => {
        if (e.key === "Escape") onCancel();
      }}
    >
      <input
        autoFocus
        value={title}
        onChange={(e) => setTitle(e.target.value)}
      />
      {task.kind === "once" && (
        <input
          type="date"
          value={dueOn}
          onChange={(e) => setDueOn(e.target.value)}
        />
      )}
      {task.kind === "interval" && (
        <>
          <input
            type="number"
            min={0}
            required
            title="Days hidden after completion"
            value={after}
            onChange={(e) => setAfter(e.target.value)}
          />
          <input
            type="number"
            min={1}
            placeholder="ramp"
            title="Days from appearing to important (empty: same as days)"
            value={ramp}
            onChange={(e) => setRamp(e.target.value)}
          />
        </>
      )}
      {task.kind === "slot" &&
        SLOTS.map((slot) => (
          <label key={slot} className="slot-pick">
            <input
              type="checkbox"
              checked={slots.includes(slot)}
              onChange={(e) =>
                setSlots(
                  e.target.checked
                    ? SLOTS.filter((s) => s === slot || slots.includes(s))
                    : slots.filter((s) => s !== slot),
                )}
            />
            {SLOT_LABEL[slot]}
          </label>
        ))}
      <button type="submit">Save</button>
      <button type="button" className="link" onClick={onCancel}>
        cancel
      </button>
    </form>
  );
}
