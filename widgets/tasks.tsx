// The viewer's list, in their own order, on the home screen. Home stamps
// X-Realmdo-User on every loader and action call (the app declares
// getTrustedHeaders), so the container answers as the person looking at the
// tile. Eager: the request needs no state, so the tile paints with data on
// first render. The top task gets a card, as in the Android app; rows are a
// per-tile setting; "done" and "add" go straight to the API.

import { r, z } from "@yourrealm/sdk";
import {
  action,
  ArrowsClockwiseIcon,
  CheckBoldIcon,
  type IconName,
  loader,
  PlusIcon,
  type Props,
  type SurfaceConfig,
} from "@yourrealm/sdk/ui";
import { useState } from "@yourrealm/sdk/preact";

export const title = "Tasks";
export const icon: IconName = "ListChecksIcon";
export const cols = 2;
export const rows = 2;

export const stateSchema = z.object({});
export const configSchema = {
  limit: r.input.number().title("Rows").describe("How many tasks to show.")
    .prefill(3).optional(),
};
type State = z.infer<typeof stateSchema>;
type Config = SurfaceConfig<typeof configSchema>;

const DEFAULT_LIMIT = 3;

const SLOT_LABEL = {
  morning: "Morning",
  before_lunch: "Before lunch",
  afternoon: "Afternoon",
  evening: "Evening",
} as const;

const task = z.object({
  id: z.number(),
  title: z.string(),
  kind: z.enum(["once", "interval", "slot"]),
  pending_slot: z.enum(["morning", "before_lunch", "afternoon", "evening"])
    .nullable(),
  due_on: z.string().nullable(),
  notes: z.string().nullable(),
  urgency: z.number(),
  important: z.boolean(),
});

export const loaders = {
  tasks: loader({
    service: "api",
    port: 3000,
    outputSchema: z.array(task),
    request: (_state: State, _config: Config) => ({
      method: "GET" as const,
      path: "/api/tasks",
    }),
    refetchInterval: 60 * 1000,
  }),
};

export const actions = {
  done: action({
    service: "api",
    port: 3000,
    inputSchema: z.object({ id: z.number() }),
    request: (input) => ({
      method: "POST" as const,
      path: `/api/tasks/${input.id}/done`,
    }),
  }),
  add: action({
    service: "api",
    port: 3000,
    inputSchema: z.object({ title: z.string() }),
    outputSchema: task,
    request: (input) => ({
      method: "POST" as const,
      path: "/api/tasks",
      body: input,
    }),
  }),
};

export default (({ loaders, actions, config }) => {
  const list = loaders.tasks;
  const [draft, setDraft] = useState("");
  const limit = config.limit ?? DEFAULT_LIMIT;

  if (list.error) {
    return (
      <div class="flex h-full items-center justify-center bg-sidebar p-3 text-center">
        <p class="text-xs text-destructive">{list.error.message}</p>
      </div>
    );
  }
  if (!list.data) {
    return (
      <div class="flex h-full items-center justify-center bg-sidebar p-3">
        <p class="text-xs text-muted-foreground">loading…</p>
      </div>
    );
  }

  const [top, ...others] = list.data.filter((t) => t.urgency >= 0);
  const rest = others.slice(0, Math.max(0, limit - 1));
  const hidden = others.length - rest.length;

  const done = async (id: number) => {
    await actions.done.mutateAsync({ id });
    list.refetch();
  };
  const submit = async () => {
    const title = draft.trim();
    if (!title) return;
    await actions.add.mutateAsync({ title });
    setDraft("");
    list.refetch();
  };

  return (
    <div class="flex h-full flex-col gap-1 bg-sidebar p-2.5 text-sm">
      {top && (
        <div class="flex shrink-0 items-center gap-2.5 rounded-2xl bg-[color-mix(in_oklab,var(--primary)_12%,transparent)] py-2.5 pl-3.5 pr-2.5">
          <div class="flex min-w-0 flex-1 flex-col gap-0.5">
            <p class="text-xs font-bold text-(color:--accent-foreground)">
              {top.pending_slot
                ? `Up next · ${SLOT_LABEL[top.pending_slot]}`
                : "Up next"}
            </p>
            <p class="truncate text-lg font-extrabold leading-tight">
              {top.title}
            </p>
          </div>
          <button
            type="button"
            aria-label={`Mark ${top.title} done`}
            disabled={actions.done.isPending}
            onClick={() => done(top.id)}
            class="flex size-11 shrink-0 items-center justify-center rounded-full bg-primary text-primary-foreground cursor-pointer disabled:cursor-default disabled:opacity-60"
          >
            <CheckBoldIcon className="size-5" />
          </button>
        </div>
      )}
      <ul class="flex min-h-0 flex-1 flex-col overflow-hidden">
        {rest.map((t) => (
          <li key={t.id} class="flex h-8 items-center gap-1.5">
            <button
              type="button"
              aria-label={`Mark ${t.title} done`}
              disabled={actions.done.isPending}
              onClick={() => done(t.id)}
              class="group flex h-8 w-8 shrink-0 items-center justify-center cursor-pointer disabled:cursor-default"
            >
              <span
                class={t.important
                  ? "size-4 rounded-full border-2 border-(color:--primary) bg-[color-mix(in_oklab,var(--primary)_12%,transparent)]"
                  : "size-4 rounded-full border-[1.5px] border-(color:--muted-foreground) group-hover:border-(color:--foreground)"}
              />
            </button>
            <span
              class={t.important
                ? "min-w-0 truncate font-bold"
                : "min-w-0 truncate font-medium"}
            >
              {t.title}
            </span>
            {t.kind === "interval" && (
              <ArrowsClockwiseIcon
                aria-label="Repeats"
                className="size-3.5 shrink-0 text-muted-foreground"
              />
            )}
            <span class="flex-1" />
            {t.notes && (
              <span
                title={t.notes}
                aria-label="Has notes"
                class="shrink-0 text-xs text-muted-foreground"
              >
                ≡
              </span>
            )}
            {t.pending_slot && (
              <span class="shrink-0 text-xs font-semibold text-muted-foreground">
                {SLOT_LABEL[t.pending_slot]}
              </span>
            )}
          </li>
        ))}
        {hidden > 0 && (
          <li class="pl-9.5 text-xs font-semibold text-muted-foreground">
            +{hidden} more
          </li>
        )}
        {others.length === 0 && (
          <li class="flex flex-1 items-center justify-center text-xs text-muted-foreground">
            {top ? "That’s all for now." : "All done for today."}
          </li>
        )}
      </ul>
      <label class="flex h-10 shrink-0 items-center gap-2 rounded-xl border border-border bg-card px-3">
        <PlusIcon className="size-4 shrink-0 text-primary" />
        <input
          value={draft}
          placeholder="Add a task"
          aria-label="Add a task"
          onInput={(e) => setDraft((e.target as HTMLInputElement).value)}
          onKeyDown={(e) => {
            if (e.key === "Enter") void submit();
          }}
          class="min-w-0 flex-1 bg-transparent text-sm placeholder:text-muted-foreground focus:outline-none"
        />
      </label>
    </div>
  );
}) satisfies Props<
  typeof loaders,
  typeof actions,
  typeof stateSchema,
  typeof configSchema
>;
