//! SQLite storage. One connection, migrations by `PRAGMA user_version`.

use crate::slots::{Slot, SlotTimes};
use rusqlite::types::{FromSql, FromSqlError, FromSqlResult, ToSql, ToSqlOutput, Type, ValueRef};
use rusqlite::{Connection, OptionalExtension, Row, params};
use std::path::Path;

/// A user row, settings included: every handler needs the time zone.
#[derive(Debug, Clone)]
pub struct User {
    pub id: i64,
    pub username: String,
    pub timezone: String,
    pub slot_times: SlotTimes,
    /// Whether a `/public/` URL secret is set (the hash itself stays inside).
    pub has_public_url: bool,
}

/// A bearer token row. The token itself is never stored, only its hash.
#[derive(Debug, Clone, serde::Serialize)]
pub struct Token {
    pub id: i64,
    pub name: String,
    pub prefix: String,
    pub created_at: i64,
    pub last_used_at: Option<i64>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Kind {
    /// Title, optional due date. Done means archived.
    Once,
    /// Recurs, measured from the last completion. Done resets the clock.
    Interval,
    /// Daily, one check per slot per day.
    Slot,
}

impl Kind {
    fn as_str(self) -> &'static str {
        match self {
            Kind::Once => "once",
            Kind::Interval => "interval",
            Kind::Slot => "slot",
        }
    }
}

impl ToSql for Kind {
    fn to_sql(&self) -> rusqlite::Result<ToSqlOutput<'_>> {
        Ok(self.as_str().into())
    }
}

impl FromSql for Kind {
    fn column_result(value: ValueRef<'_>) -> FromSqlResult<Self> {
        match value.as_str()? {
            "once" => Ok(Kind::Once),
            "interval" => Ok(Kind::Interval),
            "slot" => Ok(Kind::Slot),
            other => Err(FromSqlError::Other(
                format!("unknown task kind {other:?}").into(),
            )),
        }
    }
}

#[derive(Debug, Clone, serde::Serialize)]
pub struct Task {
    pub id: i64,
    pub title: String,
    pub kind: Kind,
    /// `YYYY-MM-DD`, one-off tasks only.
    pub due_on: Option<String>,
    /// Interval: days hidden after a completion.
    pub after_days: Option<i64>,
    /// Interval: days from appearing to important. `None` means the default.
    pub ramp_days: Option<i64>,
    /// Slot: which slots of the day, in day order.
    pub slots: Option<Vec<Slot>>,
    /// Free text; `None` when empty. Kept across completions.
    pub notes: Option<String>,
    pub created_at: i64,
    /// One-off: set when completed (archived).
    pub done_at: Option<i64>,
    /// Latest completion, any kind.
    pub last_done_at: Option<i64>,
    /// Slot: the slots already checked on the day the row was read for.
    #[serde(skip)]
    pub checked_today: Vec<Slot>,
}

const MIGRATIONS: &[&str] = &[
    // v1: users and one-off tasks.
    "CREATE TABLE users (
        id INTEGER PRIMARY KEY,
        username TEXT NOT NULL UNIQUE,
        created_at INTEGER NOT NULL
    );
    CREATE TABLE tasks (
        id INTEGER PRIMARY KEY,
        user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
        title TEXT NOT NULL,
        due_on TEXT,
        created_at INTEGER NOT NULL,
        done_at INTEGER
    );
    CREATE INDEX tasks_open ON tasks(user_id) WHERE done_at IS NULL;",
    // v2: per-user bearer tokens.
    "CREATE TABLE tokens (
        id INTEGER PRIMARY KEY,
        user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
        name TEXT NOT NULL,
        prefix TEXT NOT NULL,
        hash TEXT NOT NULL UNIQUE,
        created_at INTEGER NOT NULL,
        last_used_at INTEGER
    );",
    // v3: task kinds and a completion history. Earlier one-off completions
    // are backfilled from done_at so history starts whole.
    "ALTER TABLE tasks ADD COLUMN kind TEXT NOT NULL DEFAULT 'once';
    ALTER TABLE tasks ADD COLUMN after_days INTEGER;
    ALTER TABLE tasks ADD COLUMN ramp_days INTEGER;
    CREATE TABLE completions (
        id INTEGER PRIMARY KEY,
        task_id INTEGER NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
        completed_at INTEGER NOT NULL
    );
    CREATE INDEX completions_task ON completions(task_id, completed_at);
    INSERT INTO completions (task_id, completed_at)
        SELECT id, done_at FROM tasks WHERE done_at IS NOT NULL;",
    // v4: per-user time zone and slot times; slot tasks; a completion knows
    // the local day and slot it was for.
    "ALTER TABLE users ADD COLUMN timezone TEXT NOT NULL DEFAULT 'UTC';
    ALTER TABLE users ADD COLUMN morning_at INTEGER NOT NULL DEFAULT 420;
    ALTER TABLE users ADD COLUMN before_lunch_at INTEGER NOT NULL DEFAULT 660;
    ALTER TABLE users ADD COLUMN afternoon_at INTEGER NOT NULL DEFAULT 840;
    ALTER TABLE users ADD COLUMN evening_at INTEGER NOT NULL DEFAULT 1140;
    ALTER TABLE tasks ADD COLUMN slots TEXT;
    ALTER TABLE completions ADD COLUMN slot TEXT;
    ALTER TABLE completions ADD COLUMN day TEXT;
    CREATE INDEX completions_day ON completions(task_id, day, slot);",
    // v5: the /public/ read-only URL secret, hashed like a token.
    "ALTER TABLE users ADD COLUMN public_secret_hash TEXT;
    CREATE UNIQUE INDEX users_public_secret ON users(public_secret_hash)
        WHERE public_secret_hash IS NOT NULL;",
    // v6: free-text notes on a task.
    "ALTER TABLE tasks ADD COLUMN notes TEXT;",
];

pub fn open(path: &Path) -> rusqlite::Result<Connection> {
    let conn = Connection::open(path)?;
    conn.pragma_update(None, "journal_mode", "WAL")?;
    conn.pragma_update(None, "busy_timeout", 5000)?;
    configure(&conn)?;
    Ok(conn)
}

fn configure(conn: &Connection) -> rusqlite::Result<()> {
    conn.pragma_update(None, "foreign_keys", "ON")?;
    migrate(conn)
}

#[cfg(test)]
pub fn open_memory() -> Connection {
    let conn = Connection::open_in_memory().unwrap();
    configure(&conn).unwrap();
    conn
}

fn migrate(conn: &Connection) -> rusqlite::Result<()> {
    let version: i64 = conn.pragma_query_value(None, "user_version", |r| r.get(0))?;
    for (i, sql) in MIGRATIONS.iter().enumerate().skip(version as usize) {
        let next = i + 1;
        conn.execute_batch(&format!(
            "BEGIN; {sql} PRAGMA user_version = {next}; COMMIT;"
        ))?;
    }
    Ok(())
}

// ---- users ------------------------------------------------------------------

const USER_COLUMNS: &str = "id, username, timezone, morning_at, before_lunch_at, afternoon_at,
     evening_at, public_secret_hash IS NOT NULL";

fn user_row(r: &Row) -> rusqlite::Result<User> {
    let minutes = |i: usize| -> rusqlite::Result<u16> {
        let v: i64 = r.get(i)?;
        u16::try_from(v)
            .map_err(|e| rusqlite::Error::FromSqlConversionFailure(i, Type::Integer, Box::new(e)))
    };
    Ok(User {
        id: r.get(0)?,
        username: r.get(1)?,
        timezone: r.get(2)?,
        slot_times: SlotTimes {
            morning: minutes(3)?,
            before_lunch: minutes(4)?,
            afternoon: minutes(5)?,
            evening: minutes(6)?,
        },
        has_public_url: r.get(7)?,
    })
}

/// Returns the user, creating the row on first sight.
pub fn upsert_user(conn: &Connection, username: &str, now: i64) -> rusqlite::Result<User> {
    conn.query_row(
        &format!(
            "INSERT INTO users (username, created_at) VALUES (?1, ?2)
             ON CONFLICT(username) DO UPDATE SET username = excluded.username
             RETURNING {USER_COLUMNS}"
        ),
        params![username, now],
        user_row,
    )
}

pub fn update_settings(
    conn: &Connection,
    user_id: i64,
    timezone: &str,
    times: SlotTimes,
) -> rusqlite::Result<User> {
    conn.query_row(
        &format!(
            "UPDATE users SET timezone = ?2, morning_at = ?3, before_lunch_at = ?4,
                afternoon_at = ?5, evening_at = ?6
             WHERE id = ?1 RETURNING {USER_COLUMNS}"
        ),
        params![
            user_id,
            timezone,
            times.morning,
            times.before_lunch,
            times.afternoon,
            times.evening
        ],
        user_row,
    )
}

/// Sets or clears the `/public/` URL secret (stored hashed).
pub fn set_public_secret_hash(
    conn: &Connection,
    user_id: i64,
    hash: Option<&str>,
) -> rusqlite::Result<()> {
    conn.execute(
        "UPDATE users SET public_secret_hash = ?2 WHERE id = ?1",
        params![user_id, hash],
    )?;
    Ok(())
}

pub fn user_by_public_secret_hash(conn: &Connection, hash: &str) -> rusqlite::Result<Option<User>> {
    conn.query_row(
        &format!("SELECT {USER_COLUMNS} FROM users WHERE public_secret_hash = ?1"),
        params![hash],
        user_row,
    )
    .optional()
}

// ---- tokens -----------------------------------------------------------------

/// How stale `last_used_at` may get before a token use writes it again.
const LAST_USED_GRANULARITY: i64 = 300;

/// The user behind a token hash, touching `last_used_at` coarsely.
pub fn user_by_token_hash(
    conn: &Connection,
    hash: &str,
    now: i64,
) -> rusqlite::Result<Option<User>> {
    let row = conn
        .query_row(
            "SELECT t.id, t.last_used_at, u.id, u.username, u.timezone,
                    u.morning_at, u.before_lunch_at, u.afternoon_at, u.evening_at,
                    u.public_secret_hash IS NOT NULL
             FROM tokens t JOIN users u ON u.id = t.user_id
             WHERE t.hash = ?1",
            params![hash],
            |r| {
                let token_id: i64 = r.get(0)?;
                let last_used: Option<i64> = r.get(1)?;
                let minutes = |i: usize| -> rusqlite::Result<u16> {
                    let v: i64 = r.get(i)?;
                    u16::try_from(v).map_err(|e| {
                        rusqlite::Error::FromSqlConversionFailure(i, Type::Integer, Box::new(e))
                    })
                };
                let user = User {
                    id: r.get(2)?,
                    username: r.get(3)?,
                    timezone: r.get(4)?,
                    slot_times: SlotTimes {
                        morning: minutes(5)?,
                        before_lunch: minutes(6)?,
                        afternoon: minutes(7)?,
                        evening: minutes(8)?,
                    },
                    has_public_url: r.get(9)?,
                };
                Ok((token_id, last_used, user))
            },
        )
        .optional()?;
    let Some((token_id, last_used, user)) = row else {
        return Ok(None);
    };
    if last_used.is_none_or(|t| now - t >= LAST_USED_GRANULARITY) {
        conn.execute(
            "UPDATE tokens SET last_used_at = ?2 WHERE id = ?1",
            params![token_id, now],
        )?;
    }
    Ok(Some(user))
}

const TOKEN_COLUMNS: &str = "id, name, prefix, created_at, last_used_at";

fn token_row(r: &Row) -> rusqlite::Result<Token> {
    Ok(Token {
        id: r.get(0)?,
        name: r.get(1)?,
        prefix: r.get(2)?,
        created_at: r.get(3)?,
        last_used_at: r.get(4)?,
    })
}

pub fn list_tokens(conn: &Connection, user_id: i64) -> rusqlite::Result<Vec<Token>> {
    let mut stmt = conn.prepare(&format!(
        "SELECT {TOKEN_COLUMNS} FROM tokens WHERE user_id = ?1 ORDER BY id"
    ))?;
    let rows = stmt.query_map(params![user_id], token_row)?;
    rows.collect()
}

pub fn insert_token(
    conn: &Connection,
    user_id: i64,
    name: &str,
    prefix: &str,
    hash: &str,
    now: i64,
) -> rusqlite::Result<Token> {
    conn.query_row(
        &format!(
            "INSERT INTO tokens (user_id, name, prefix, hash, created_at)
             VALUES (?1, ?2, ?3, ?4, ?5) RETURNING {TOKEN_COLUMNS}"
        ),
        params![user_id, name, prefix, hash, now],
        token_row,
    )
}

/// `true` when a row was deleted.
pub fn delete_token(conn: &Connection, user_id: i64, id: i64) -> rusqlite::Result<bool> {
    let n = conn.execute(
        "DELETE FROM tokens WHERE id = ?1 AND user_id = ?2",
        params![id, user_id],
    )?;
    Ok(n > 0)
}

// ---- tasks ------------------------------------------------------------------

/// A task's own columns, its latest completion, and the slots checked on the
/// day bound as `:day`. `task_row` reads exactly this.
const TASK_SELECT: &str =
    "SELECT t.id, t.title, t.kind, t.due_on, t.after_days, t.ramp_days, t.slots,
        t.created_at, t.done_at, t.notes,
        (SELECT MAX(c.completed_at) FROM completions c WHERE c.task_id = t.id),
        (SELECT GROUP_CONCAT(c.slot) FROM completions c
            WHERE c.task_id = t.id AND c.day = :day AND c.slot IS NOT NULL)
     FROM tasks t";

fn task_row(r: &Row) -> rusqlite::Result<Task> {
    let slots: Option<String> = r.get(6)?;
    let slots = slots
        .map(|s| serde_json::from_str::<Vec<Slot>>(&s))
        .transpose()
        .map_err(|e| rusqlite::Error::FromSqlConversionFailure(6, Type::Text, Box::new(e)))?;
    let checked: Option<String> = r.get(11)?;
    // GROUP_CONCAT has no order; put the slots back in day order.
    let mut checked_today: Vec<Slot> = checked
        .map(|s| s.split(',').filter_map(Slot::parse).collect())
        .unwrap_or_default();
    checked_today.sort();
    Ok(Task {
        id: r.get(0)?,
        title: r.get(1)?,
        kind: r.get(2)?,
        due_on: r.get(3)?,
        after_days: r.get(4)?,
        ramp_days: r.get(5)?,
        slots,
        notes: r.get(9)?,
        created_at: r.get(7)?,
        done_at: r.get(8)?,
        last_done_at: r.get(10)?,
        checked_today,
    })
}

/// One task, with its slot checks for `day` (`YYYY-MM-DD`, the user's day).
pub fn get_task(
    conn: &Connection,
    user_id: i64,
    id: i64,
    day: &str,
) -> rusqlite::Result<Option<Task>> {
    conn.query_row(
        &format!("{TASK_SELECT} WHERE t.id = :id AND t.user_id = :user"),
        rusqlite::named_params! { ":id": id, ":user": user_id, ":day": day },
        task_row,
    )
    .optional()
}

pub fn open_tasks(conn: &Connection, user_id: i64, day: &str) -> rusqlite::Result<Vec<Task>> {
    let mut stmt = conn.prepare(&format!(
        "{TASK_SELECT} WHERE t.user_id = :user AND t.done_at IS NULL ORDER BY t.id"
    ))?;
    let rows = stmt.query_map(
        rusqlite::named_params! { ":user": user_id, ":day": day },
        task_row,
    )?;
    rows.collect()
}

pub struct NewTask<'a> {
    pub title: &'a str,
    pub kind: Kind,
    pub due_on: Option<&'a str>,
    pub after_days: Option<i64>,
    pub ramp_days: Option<i64>,
    pub slots: Option<Vec<Slot>>,
    pub notes: Option<&'a str>,
}

pub fn insert_task(
    conn: &Connection,
    user_id: i64,
    new: &NewTask,
    now: i64,
    day: &str,
) -> rusqlite::Result<Task> {
    let slots = new
        .slots
        .as_ref()
        .map(|s| serde_json::to_string(s).expect("slots serialize"));
    let id: i64 = conn.query_row(
        "INSERT INTO tasks (user_id, title, kind, due_on, after_days, ramp_days, slots, notes,
            created_at)
         VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9) RETURNING id",
        params![
            user_id,
            new.title,
            new.kind,
            new.due_on,
            new.after_days,
            new.ramp_days,
            slots,
            new.notes,
            now
        ],
        |r| r.get(0),
    )?;
    get_task(conn, user_id, id, day).map(|t| t.expect("just inserted"))
}

/// Changed fields of a task. The kind itself never changes.
pub struct TaskUpdate<'a> {
    pub title: &'a str,
    pub due_on: Option<&'a str>,
    pub after_days: Option<i64>,
    pub ramp_days: Option<i64>,
    pub slots: Option<Vec<Slot>>,
    pub notes: Option<&'a str>,
}

/// Rewrites a task's mutable columns. `None` when it is not this user's.
pub fn update_task(
    conn: &Connection,
    user_id: i64,
    id: i64,
    upd: &TaskUpdate,
    day: &str,
) -> rusqlite::Result<Option<Task>> {
    let slots = upd
        .slots
        .as_ref()
        .map(|s| serde_json::to_string(s).expect("slots serialize"));
    let n = conn.execute(
        "UPDATE tasks SET title = ?3, due_on = ?4, after_days = ?5, ramp_days = ?6, slots = ?7,
            notes = ?8
         WHERE id = ?1 AND user_id = ?2",
        params![
            id,
            user_id,
            upd.title,
            upd.due_on,
            upd.after_days,
            upd.ramp_days,
            slots,
            upd.notes
        ],
    )?;
    if n == 0 {
        return Ok(None);
    }
    get_task(conn, user_id, id, day)
}

/// Records a completion for `day` (and `slot`, for slot tasks). A one-off is
/// archived as well; an interval task stays open with its clock reset; a
/// slot check is one row per (day, slot). All idempotent. `None` when the
/// task is not this user's.
pub fn complete_task(
    conn: &Connection,
    user_id: i64,
    id: i64,
    now: i64,
    day: &str,
    slot: Option<Slot>,
) -> rusqlite::Result<Option<Task>> {
    let tx = conn.unchecked_transaction()?;
    let Some(task) = get_task(&tx, user_id, id, day)? else {
        return Ok(None);
    };
    if task.done_at.is_none() {
        let slot_name = slot.map(Slot::as_str);
        tx.execute(
            "INSERT INTO completions (task_id, completed_at, slot, day)
             SELECT ?1, ?2, ?3, ?4
             WHERE NOT EXISTS (
                SELECT 1 FROM completions WHERE task_id = ?1 AND day = ?4 AND slot IS ?3
             ) OR ?3 IS NULL",
            params![id, now, slot_name, day],
        )?;
        if task.kind == Kind::Once {
            tx.execute(
                "UPDATE tasks SET done_at = ?2 WHERE id = ?1",
                params![id, now],
            )?;
        }
    }
    let task = get_task(&tx, user_id, id, day)?;
    tx.commit()?;
    Ok(task)
}

/// `true` when a row was deleted.
pub fn delete_task(conn: &Connection, user_id: i64, id: i64) -> rusqlite::Result<bool> {
    let n = conn.execute(
        "DELETE FROM tasks WHERE id = ?1 AND user_id = ?2",
        params![id, user_id],
    )?;
    Ok(n > 0)
}

#[cfg(test)]
mod tests {
    use super::*;

    const DAY: &str = "2026-08-30";

    fn memory() -> Connection {
        open_memory()
    }

    fn once<'a>(title: &'a str, due_on: Option<&'a str>) -> NewTask<'a> {
        NewTask {
            title,
            kind: Kind::Once,
            due_on,
            after_days: None,
            ramp_days: None,
            slots: None,
            notes: None,
        }
    }

    fn every(title: &str, after: i64, ramp: Option<i64>) -> NewTask<'_> {
        NewTask {
            title,
            kind: Kind::Interval,
            due_on: None,
            after_days: Some(after),
            ramp_days: ramp,
            slots: None,
            notes: None,
        }
    }

    fn daily<'a>(title: &'a str, slots: &[Slot]) -> NewTask<'a> {
        NewTask {
            title,
            kind: Kind::Slot,
            due_on: None,
            after_days: None,
            ramp_days: None,
            slots: Some(slots.to_vec()),
            notes: None,
        }
    }

    fn completions(conn: &Connection) -> i64 {
        conn.query_row("SELECT COUNT(*) FROM completions", [], |r| r.get(0))
            .unwrap()
    }

    #[test]
    fn migrate_is_idempotent() {
        let conn = memory();
        migrate(&conn).unwrap();
        let v: i64 = conn
            .pragma_query_value(None, "user_version", |r| r.get(0))
            .unwrap();
        assert_eq!(v as usize, MIGRATIONS.len());
    }

    #[test]
    fn users_start_with_default_settings_and_keep_updates() {
        let conn = memory();
        let a = upsert_user(&conn, "max", 1).unwrap();
        assert_eq!(a.timezone, "UTC");
        assert_eq!(a.slot_times, SlotTimes::DEFAULT);
        let b = upsert_user(&conn, "max", 2).unwrap();
        assert_eq!(a.id, b.id);
        assert_ne!(upsert_user(&conn, "eve", 3).unwrap().id, a.id);

        let times = SlotTimes {
            morning: 360,
            ..SlotTimes::DEFAULT
        };
        let u = update_settings(&conn, a.id, "Europe/Stockholm", times).unwrap();
        assert_eq!(u.timezone, "Europe/Stockholm");
        assert_eq!(u.slot_times.morning, 360);
        let again = upsert_user(&conn, "max", 4).unwrap();
        assert_eq!(again.timezone, "Europe/Stockholm", "upsert keeps settings");
    }

    #[test]
    fn once_tasks_are_per_user_and_archive_on_completion() {
        let conn = memory();
        let max = upsert_user(&conn, "max", 1).unwrap();
        let eve = upsert_user(&conn, "eve", 1).unwrap();
        let t = insert_task(&conn, max.id, &once("clean", Some("2026-09-01")), 10, DAY).unwrap();
        assert_eq!(t.kind, Kind::Once);
        assert_eq!(t.last_done_at, None);
        assert_eq!(open_tasks(&conn, max.id, DAY).unwrap().len(), 1);
        assert!(open_tasks(&conn, eve.id, DAY).unwrap().is_empty());

        assert!(
            complete_task(&conn, eve.id, t.id, 20, DAY, None)
                .unwrap()
                .is_none()
        );
        assert!(!delete_task(&conn, eve.id, t.id).unwrap());

        let done = complete_task(&conn, max.id, t.id, 20, DAY, None)
            .unwrap()
            .unwrap();
        assert_eq!(done.done_at, Some(20));
        assert_eq!(done.last_done_at, Some(20));
        let again = complete_task(&conn, max.id, t.id, 30, DAY, None)
            .unwrap()
            .unwrap();
        assert_eq!(again.done_at, Some(20), "done is idempotent");
        assert_eq!(completions(&conn), 1, "no second completion row");
        assert!(open_tasks(&conn, max.id, DAY).unwrap().is_empty());
        assert!(delete_task(&conn, max.id, t.id).unwrap());
    }

    #[test]
    fn interval_tasks_stay_open_and_reset_their_clock() {
        let conn = memory();
        let max = upsert_user(&conn, "max", 1).unwrap();
        let t = insert_task(&conn, max.id, &every("bathroom", 6, Some(5)), 10, DAY).unwrap();
        assert_eq!(
            (t.kind, t.after_days, t.ramp_days),
            (Kind::Interval, Some(6), Some(5))
        );
        assert_eq!(t.last_done_at, None);

        let first = complete_task(&conn, max.id, t.id, 100, DAY, None)
            .unwrap()
            .unwrap();
        assert_eq!(first.done_at, None, "interval tasks never archive");
        assert_eq!(first.last_done_at, Some(100));
        let second = complete_task(&conn, max.id, t.id, 200, DAY, None)
            .unwrap()
            .unwrap();
        assert_eq!(second.last_done_at, Some(200), "latest completion wins");
        assert_eq!(open_tasks(&conn, max.id, DAY).unwrap().len(), 1);
        assert_eq!(completions(&conn), 2);
        assert!(delete_task(&conn, max.id, t.id).unwrap());
        assert_eq!(completions(&conn), 0, "completions go with the task");
    }

    #[test]
    fn slot_checks_are_one_per_day_and_slot() {
        let conn = memory();
        let max = upsert_user(&conn, "max", 1).unwrap();
        let t = insert_task(
            &conn,
            max.id,
            &daily("teeth", &[Slot::Morning, Slot::Evening]),
            10,
            DAY,
        )
        .unwrap();
        assert_eq!(
            t.slots.as_deref(),
            Some(&[Slot::Morning, Slot::Evening][..])
        );
        assert!(t.checked_today.is_empty());

        let t = complete_task(&conn, max.id, t.id, 100, DAY, Some(Slot::Morning))
            .unwrap()
            .unwrap();
        assert_eq!(t.checked_today, vec![Slot::Morning]);
        assert_eq!(t.done_at, None);
        // Same day, same slot: nothing new. Same slot, next day: a new row.
        complete_task(&conn, max.id, t.id, 110, DAY, Some(Slot::Morning)).unwrap();
        assert_eq!(completions(&conn), 1);
        let tomorrow = complete_task(&conn, max.id, t.id, 200, "2026-08-31", Some(Slot::Morning))
            .unwrap()
            .unwrap();
        assert_eq!(completions(&conn), 2);
        assert_eq!(tomorrow.checked_today, vec![Slot::Morning]);
        assert_eq!(tomorrow.last_done_at, Some(200));

        let t = complete_task(&conn, max.id, t.id, 300, DAY, Some(Slot::Evening))
            .unwrap()
            .unwrap();
        assert_eq!(t.checked_today, vec![Slot::Morning, Slot::Evening]);
        assert_eq!(
            open_tasks(&conn, max.id, DAY).unwrap()[0]
                .checked_today
                .len(),
            2
        );
        assert!(
            open_tasks(&conn, max.id, "2026-09-01").unwrap()[0]
                .checked_today
                .is_empty()
        );
    }

    #[test]
    fn tasks_can_be_rewritten_within_their_kind() {
        let conn = memory();
        let max = upsert_user(&conn, "max", 1).unwrap();
        let eve = upsert_user(&conn, "eve", 1).unwrap();
        let t = insert_task(&conn, max.id, &once("clean", Some("2026-09-01")), 10, DAY).unwrap();
        let upd = TaskUpdate {
            title: "scrub",
            due_on: None,
            after_days: None,
            ramp_days: None,
            slots: None,
            notes: Some("the blue one"),
        };
        assert!(
            update_task(&conn, eve.id, t.id, &upd, DAY)
                .unwrap()
                .is_none()
        );
        let t = update_task(&conn, max.id, t.id, &upd, DAY)
            .unwrap()
            .unwrap();
        assert_eq!((t.title.as_str(), t.due_on), ("scrub", None));
        assert_eq!(t.notes.as_deref(), Some("the blue one"));
        let done = complete_task(&conn, max.id, t.id, 20, DAY, None)
            .unwrap()
            .unwrap();
        assert_eq!(
            done.notes.as_deref(),
            Some("the blue one"),
            "archived with it"
        );
    }

    #[test]
    fn public_secret_hash_finds_exactly_one_user() {
        let conn = memory();
        let max = upsert_user(&conn, "max", 1).unwrap();
        assert!(!max.has_public_url);
        assert!(user_by_public_secret_hash(&conn, "h").unwrap().is_none());
        set_public_secret_hash(&conn, max.id, Some("h")).unwrap();
        let found = user_by_public_secret_hash(&conn, "h").unwrap().unwrap();
        assert_eq!(found.id, max.id);
        assert!(found.has_public_url);
        set_public_secret_hash(&conn, max.id, Some("h2")).unwrap();
        assert!(
            user_by_public_secret_hash(&conn, "h").unwrap().is_none(),
            "regenerate replaces"
        );
        set_public_secret_hash(&conn, max.id, None).unwrap();
        assert!(user_by_public_secret_hash(&conn, "h2").unwrap().is_none());
    }

    #[test]
    fn v3_backfills_completions_from_done_at() {
        let conn = Connection::open_in_memory().unwrap();
        conn.pragma_update(None, "foreign_keys", "ON").unwrap();
        // Apply v1 + v2 by hand, write an archived one-off, then migrate on.
        for (i, sql) in MIGRATIONS.iter().take(2).enumerate() {
            conn.execute_batch(&format!(
                "BEGIN; {sql} PRAGMA user_version = {}; COMMIT;",
                i + 1
            ))
            .unwrap();
        }
        conn.execute_batch(
            "INSERT INTO users (id, username, created_at) VALUES (1, 'max', 1);
             INSERT INTO tasks (id, user_id, title, created_at, done_at) VALUES (7, 1, 'old', 1, 50);",
        )
        .unwrap();
        migrate(&conn).unwrap();
        let t = get_task(&conn, 1, 7, DAY).unwrap().unwrap();
        assert_eq!(t.kind, Kind::Once);
        assert_eq!(t.last_done_at, Some(50));
        assert_eq!(upsert_user(&conn, "max", 2).unwrap().timezone, "UTC");
    }

    #[test]
    fn tokens_resolve_to_their_user_and_can_be_revoked() {
        let conn = memory();
        let max = upsert_user(&conn, "max", 1).unwrap();
        let eve = upsert_user(&conn, "eve", 1).unwrap();
        let t = insert_token(&conn, max.id, "phone", "rdo_12345678", "h1", 10).unwrap();
        assert_eq!(t.last_used_at, None);

        let found = user_by_token_hash(&conn, "h1", 20).unwrap().unwrap();
        assert_eq!(found.id, max.id);
        assert_eq!(found.slot_times, SlotTimes::DEFAULT);
        assert_eq!(
            list_tokens(&conn, max.id).unwrap()[0].last_used_at,
            Some(20)
        );
        // Within the granularity window the timestamp is left alone.
        user_by_token_hash(&conn, "h1", 30).unwrap();
        assert_eq!(
            list_tokens(&conn, max.id).unwrap()[0].last_used_at,
            Some(20)
        );
        user_by_token_hash(&conn, "h1", 20 + LAST_USED_GRANULARITY).unwrap();
        assert_eq!(
            list_tokens(&conn, max.id).unwrap()[0].last_used_at,
            Some(20 + LAST_USED_GRANULARITY)
        );

        assert!(user_by_token_hash(&conn, "nope", 20).unwrap().is_none());
        assert!(list_tokens(&conn, eve.id).unwrap().is_empty());
        assert!(!delete_token(&conn, eve.id, t.id).unwrap());
        assert!(delete_token(&conn, max.id, t.id).unwrap());
        assert!(user_by_token_hash(&conn, "h1", 20).unwrap().is_none());
    }
}
