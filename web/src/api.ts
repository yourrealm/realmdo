export type Kind = "once" | "interval" | "slot";
export type Slot = "morning" | "before_lunch" | "afternoon" | "evening";
export type SlotState = "done" | "upcoming" | "current" | "overdue";

export const SLOTS: Slot[] = [
  "morning",
  "before_lunch",
  "afternoon",
  "evening",
];
export const SLOT_LABEL: Record<Slot, string> = {
  morning: "morning",
  before_lunch: "before lunch",
  afternoon: "afternoon",
  evening: "evening",
};

export interface SlotStatus {
  slot: Slot;
  state: SlotState;
}

export interface Task {
  id: number;
  title: string;
  kind: Kind;
  due_on: string | null;
  after_days: number | null;
  ramp_days: number | null;
  slots: Slot[] | null;
  created_at: number;
  done_at: number | null;
  last_done_at: number | null;
  urgency: number;
  important: boolean;
  today: SlotStatus[] | null;
  pending_slot: Slot | null;
}

export interface NewTask {
  title: string;
  kind: Kind;
  due_on?: string | null;
  after_days?: number;
  ramp_days?: number;
  slots?: Slot[];
}

/** Absent fields keep their value; due_on/ramp_days null clears them. */
export interface TaskPatch {
  title?: string;
  due_on?: string | null;
  after_days?: number;
  ramp_days?: number | null;
  slots?: Slot[];
}

export interface Settings {
  timezone: string;
  slot_times: Record<Slot, string>;
}

export interface Me {
  username: string;
  via: "session" | "token";
}

export interface Token {
  id: number;
  name: string;
  prefix: string;
  created_at: number;
  last_used_at: number | null;
}

/** The secret is returned once, at creation. */
export interface MintedToken extends Token {
  secret: string;
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(path, {
    ...init,
    headers: { "Content-Type": "application/json", ...init?.headers },
  });
  if (!res.ok) {
    const body: { error?: string } = await res.json().catch(() => ({}));
    throw new Error(body.error ?? `${res.status} ${res.statusText}`);
  }
  return res.status === 204 ? (undefined as T) : res.json();
}

export const api = {
  me: () => request<Me>("/api/me"),
  settings: () => request<Settings>("/api/settings"),
  saveSettings: (s: Settings) =>
    request<Settings>("/api/settings", {
      method: "PUT",
      body: JSON.stringify(s),
    }),
  tasks: () => request<Task[]>("/api/tasks"),
  create: (task: NewTask) =>
    request<Task>("/api/tasks", { method: "POST", body: JSON.stringify(task) }),
  done: (id: number, slot?: Slot) =>
    request<Task>(`/api/tasks/${id}/done`, {
      method: "POST",
      body: slot ? JSON.stringify({ slot }) : undefined,
    }),
  remove: (id: number) =>
    request<void>(`/api/tasks/${id}`, { method: "DELETE" }),
  tokens: () => request<Token[]>("/api/tokens"),
  createToken: (name: string) =>
    request<MintedToken>("/api/tokens", {
      method: "POST",
      body: JSON.stringify({ name }),
    }),
  revokeToken: (id: number) =>
    request<void>(`/api/tokens/${id}`, { method: "DELETE" }),
  update: (id: number, patch: TaskPatch) =>
    request<Task>(`/api/tasks/${id}`, {
      method: "PATCH",
      body: JSON.stringify(patch),
    }),
  publicUrl: () => request<{ enabled: boolean }>("/api/public-url"),
  mintPublicUrl: () =>
    request<{ path: string }>("/api/public-url", { method: "POST" }),
  disablePublicUrl: () =>
    request<void>("/api/public-url", { method: "DELETE" }),
};
