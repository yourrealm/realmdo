// The viewer's list, in their own order, on the home screen. Home stamps
// X-Realmdo-User on every loader and action call (the app declares
// getTrustedHeaders), so the container answers as the person looking at the
// tile. Eager: the request needs no state, so the tile paints with data on
// first render. Rows are a per-tile setting; "done" and "add" go straight to
// the API.

import { r, z } from "@yourrealm/sdk";
import {
  action,
  type IconName,
  loader,
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
    .prefill(6).optional(),
};
type State = z.infer<typeof stateSchema>;
type Config = SurfaceConfig<typeof configSchema>;

const DEFAULT_LIMIT = 6;

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

  const visible = list.data.filter((t) => t.urgency >= 0);
  const shown = visible.slice(0, limit);
  const hidden = visible.length - shown.length;

  const submit = async () => {
    const title = draft.trim();
    if (!title) return;
    await actions.add.mutateAsync({ title });
    setDraft("");
    list.refetch();
  };

  return (
    <div class="flex h-full flex-col bg-sidebar p-2 text-sm">
      <ul class="flex-1 overflow-hidden">
        {shown.map((t) => (
          <li key={t.id} class="flex items-center gap-2 py-0.5">
            <button
              type="button"
              aria-label="Done"
              disabled={actions.done.isPending}
              onClick={async () => {
                await actions.done.mutateAsync({ id: t.id });
                list.refetch();
              }}
              class="text-muted-foreground hover:text-foreground"
            >
              ○
            </button>
            <span
              class={t.important
                ? "truncate font-semibold text-destructive"
                : "truncate"}
            >
              {t.kind === "interval" && (
                <span class="mr-1 text-muted-foreground">↻</span>
              )}
              {t.title}
              {t.pending_slot && (
                <span class="ml-1 text-xs text-muted-foreground">
                  {t.pending_slot.replace("_", " ")}
                </span>
              )}
            </span>
            {t.notes && (
              <span
                title={t.notes}
                aria-label="Has notes"
                class="ml-auto shrink-0 text-xs text-muted-foreground"
              >
                ≡
              </span>
            )}
          </li>
        ))}
        {shown.length === 0 && (
          <li class="text-xs text-muted-foreground">Nothing to do.</li>
        )}
      </ul>
      {hidden > 0 && (
        <p class="text-[10px] text-muted-foreground">+{hidden} more</p>
      )}
      <input
        value={draft}
        placeholder="Add a task…"
        onInput={(e) => setDraft((e.target as HTMLInputElement).value)}
        onKeyDown={(e) => {
          if (e.key === "Enter") void submit();
        }}
        class="mt-1 w-full rounded border border-border bg-transparent px-1.5 py-0.5 text-xs focus:outline-none"
      />
    </div>
  );
}) satisfies Props<
  typeof loaders,
  typeof actions,
  typeof stateSchema,
  typeof configSchema
>;
