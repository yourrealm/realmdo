//! JSON API under /api/.
//!
//! Identity, in order of precedence:
//! 1. `X-Realmdo-User`, the header the gate injects for a Home session (and
//!    strips from every client). Only Caddy and Home's widget-loader proxy
//!    can reach the container, and neither lets a client choose it.
//! 2. `Authorization: Bearer rdo_...`, a per-user token minted under
//!    Settings. The gate admits `/api/` when a bearer is present; the token
//!    is checked here.
//!
//! Token management needs (1): a leaked device token cannot mint more tokens.
//!
//! Every "today" and "now" is the user's local time (their time zone setting).

use crate::slots::{Slot, SlotTimes, format_hhmm, parse_hhmm};
use crate::urgency::SlotState;
use crate::{clock, date, db, token, urgency};
use axum::body::Bytes;
use axum::extract::rejection::JsonRejection;
use axum::extract::{FromRequest, FromRequestParts, Path, Request, State};
use axum::http::StatusCode;
use axum::http::request::Parts;
use axum::response::{IntoResponse, Response};
use axum::routing::{delete, get, post};
use axum::{Json, Router};
use jiff::tz::TimeZone;
use rusqlite::Connection;
use serde::{Deserialize, Serialize};
use std::sync::{Arc, Mutex};
use std::time::{SystemTime, UNIX_EPOCH};

/// Must match `getTrustedHeaders` in realm.tsx.
const USER_HEADER: &str = "x-realmdo-user";
const MAX_USERNAME: usize = 64;
const MAX_TITLE: usize = 500;
const MAX_TOKEN_NAME: usize = 64;
const MAX_DAYS: i64 = 3650;

type Clock = Arc<dyn Fn() -> i64 + Send + Sync>;

#[derive(Clone)]
pub struct AppState {
    db: Arc<Mutex<Connection>>,
    now: Clock,
}

fn system_now() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs() as i64)
        .unwrap_or(0)
}

impl AppState {
    pub fn new(conn: Connection) -> Self {
        Self::with_clock(conn, Arc::new(system_now))
    }

    fn with_clock(conn: Connection, now: Clock) -> Self {
        Self {
            db: Arc::new(Mutex::new(conn)),
            now,
        }
    }

    fn now(&self) -> i64 {
        (self.now)()
    }

    /// Runs a query off the async runtime. One connection, so calls serialize.
    async fn db<T, F>(&self, f: F) -> Result<T, ApiError>
    where
        F: FnOnce(&Connection) -> rusqlite::Result<T> + Send + 'static,
        T: Send + 'static,
    {
        let db = Arc::clone(&self.db);
        tokio::task::spawn_blocking(move || {
            let conn = db.lock().unwrap_or_else(|e| e.into_inner());
            f(&conn)
        })
        .await
        .map_err(|e| ApiError::Internal(e.to_string()))?
        .map_err(ApiError::from)
    }
}

pub fn router(state: AppState) -> Router {
    Router::new()
        .route("/healthz", get(|| async { "ok" }))
        .route("/api/me", get(me))
        .route("/api/settings", get(get_settings).put(put_settings))
        .route("/api/tasks", get(list_tasks).post(create_task))
        .route("/api/tasks/{id}/done", post(done_task))
        .route("/api/tasks/{id}", delete(delete_task).patch(patch_task))
        .route(
            "/api/public-url",
            get(public_url_status)
                .post(mint_public_url)
                .delete(disable_public_url),
        )
        .route("/public/{secret}/today", get(public_today))
        .route("/api/tokens", get(list_tokens).post(create_token))
        .route("/api/tokens/{id}", delete(delete_token))
        .with_state(state)
}

enum ApiError {
    Unauthorized,
    Forbidden(&'static str),
    NotFound,
    BadRequest(String),
    Internal(String),
}

fn bad(msg: &str) -> ApiError {
    ApiError::BadRequest(msg.to_string())
}

impl From<rusqlite::Error> for ApiError {
    fn from(e: rusqlite::Error) -> Self {
        ApiError::Internal(e.to_string())
    }
}

impl IntoResponse for ApiError {
    fn into_response(self) -> Response {
        let (status, message) = match self {
            ApiError::Unauthorized => (StatusCode::UNAUTHORIZED, "sign in required".to_string()),
            ApiError::Forbidden(m) => (StatusCode::FORBIDDEN, m.to_string()),
            ApiError::NotFound => (StatusCode::NOT_FOUND, "not found".to_string()),
            ApiError::BadRequest(m) => (StatusCode::BAD_REQUEST, m),
            ApiError::Internal(m) => {
                eprintln!("internal error: {m}");
                (
                    StatusCode::INTERNAL_SERVER_ERROR,
                    "internal error".to_string(),
                )
            }
        };
        (status, Json(serde_json::json!({ "error": message }))).into_response()
    }
}

/// `Json`, but a malformed body is a 400 with the same `{ "error" }` shape
/// as every other failure, instead of axum's plain-text 422.
struct ApiJson<T>(T);

impl<T, S> FromRequest<S> for ApiJson<T>
where
    Json<T>: FromRequest<S, Rejection = JsonRejection>,
    S: Send + Sync,
{
    type Rejection = ApiError;

    async fn from_request(req: Request, state: &S) -> Result<Self, ApiError> {
        match Json::<T>::from_request(req, state).await {
            Ok(Json(value)) => Ok(ApiJson(value)),
            Err(rejection) => Err(ApiError::BadRequest(rejection.body_text())),
        }
    }
}

#[derive(Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "lowercase")]
enum Via {
    Session,
    Token,
}

/// The calling user, created on first sight for a session.
struct Identity {
    user: db::User,
    via: Via,
}

impl FromRequestParts<AppState> for Identity {
    type Rejection = ApiError;

    async fn from_request_parts(parts: &mut Parts, state: &AppState) -> Result<Self, ApiError> {
        let now = state.now();
        let header = parts
            .headers
            .get(USER_HEADER)
            .and_then(|v| v.to_str().ok())
            .map(str::trim)
            .filter(|s| !s.is_empty() && s.len() <= MAX_USERNAME)
            .map(str::to_string);
        if let Some(username) = header {
            let user = state
                .db(move |c| db::upsert_user(c, &username, now))
                .await?;
            return Ok(Identity {
                user,
                via: Via::Session,
            });
        }
        let bearer = parts
            .headers
            .get(axum::http::header::AUTHORIZATION)
            .and_then(|v| v.to_str().ok())
            .and_then(|v| v.strip_prefix("Bearer "))
            .map(str::trim)
            .filter(|t| !t.is_empty())
            .ok_or(ApiError::Unauthorized)?;
        let hash = token::hash(bearer);
        let user = state
            .db(move |c| db::user_by_token_hash(c, &hash, now))
            .await?
            .ok_or(ApiError::Unauthorized)?;
        Ok(Identity {
            user,
            via: Via::Token,
        })
    }
}

/// An identity that arrived through a Home session, for token management.
struct Session(db::User);

impl FromRequestParts<AppState> for Session {
    type Rejection = ApiError;

    async fn from_request_parts(parts: &mut Parts, state: &AppState) -> Result<Self, ApiError> {
        let id = Identity::from_request_parts(parts, state).await?;
        match id.via {
            Via::Session => Ok(Session(id.user)),
            Via::Token => Err(ApiError::Forbidden(
                "token management needs a browser session",
            )),
        }
    }
}

// ---- views ------------------------------------------------------------------

/// The user's clock for one request.
struct Ctx {
    user: db::User,
    tz: TimeZone,
    now: i64,
    local: clock::Local,
}

impl Ctx {
    fn new(state: &AppState, user: db::User) -> Ctx {
        let tz = clock::zone(&user.timezone);
        let now = state.now();
        let local = clock::local(now, &tz);
        Ctx {
            user,
            tz,
            now,
            local,
        }
    }
}

#[derive(Serialize, Clone, Copy)]
struct SlotStatus {
    slot: Slot,
    state: SlotState,
}

#[derive(Serialize)]
struct TaskView {
    #[serde(flatten)]
    task: db::Task,
    urgency: f64,
    important: bool,
    /// Slot tasks: each of today's slots and where it stands.
    today: Option<Vec<SlotStatus>>,
    /// Slot tasks: the slot a bare "done" would check.
    pending_slot: Option<Slot>,
}

fn slot_statuses(task: &db::Task, times: &SlotTimes, minute: u16) -> Vec<SlotStatus> {
    task.slots
        .iter()
        .flatten()
        .map(|&slot| SlotStatus {
            slot,
            state: urgency::slot_state(slot, times, minute, task.checked_today.contains(&slot)),
        })
        .collect()
}

/// Overdue first (earliest), then current, then the next upcoming.
fn pending_slot(statuses: &[SlotStatus]) -> Option<Slot> {
    [SlotState::Overdue, SlotState::Current, SlotState::Upcoming]
        .into_iter()
        .find_map(|want| statuses.iter().find(|s| s.state == want).map(|s| s.slot))
}

fn view(task: db::Task, ctx: &Ctx) -> TaskView {
    let today = ctx.local.day;
    let (urgency, statuses) = match task.kind {
        db::Kind::Once => (
            urgency::once(task.due_on.as_deref().and_then(date::parse_day), today),
            None,
        ),
        db::Kind::Interval => {
            // The clock starts at creation until the first completion.
            let last_day = clock::day_of(task.last_done_at.unwrap_or(task.created_at), &ctx.tz);
            (
                urgency::interval(
                    task.after_days.unwrap_or(0),
                    task.ramp_days,
                    last_day,
                    today,
                ),
                None,
            )
        }
        db::Kind::Slot => {
            let statuses = slot_statuses(&task, &ctx.user.slot_times, ctx.local.minute);
            (
                urgency::slots(statuses.iter().map(|s| s.state)),
                Some(statuses),
            )
        }
    };
    TaskView {
        task,
        urgency,
        important: urgency::is_important(urgency),
        pending_slot: statuses.as_deref().and_then(pending_slot),
        today: statuses,
    }
}

// ---- me + settings ----------------------------------------------------------

#[derive(Serialize)]
struct Me {
    username: String,
    via: Via,
}

async fn me(id: Identity) -> Json<Me> {
    Json(Me {
        username: id.user.username,
        via: id.via,
    })
}

#[derive(Serialize, Deserialize)]
struct SlotTimesBody {
    morning: String,
    before_lunch: String,
    afternoon: String,
    evening: String,
}

#[derive(Serialize, Deserialize)]
struct SettingsBody {
    timezone: String,
    slot_times: SlotTimesBody,
}

fn settings_view(user: &db::User) -> SettingsBody {
    let t = user.slot_times;
    SettingsBody {
        timezone: user.timezone.clone(),
        slot_times: SlotTimesBody {
            morning: format_hhmm(t.morning),
            before_lunch: format_hhmm(t.before_lunch),
            afternoon: format_hhmm(t.afternoon),
            evening: format_hhmm(t.evening),
        },
    }
}

async fn get_settings(id: Identity) -> Json<SettingsBody> {
    Json(settings_view(&id.user))
}

async fn put_settings(
    State(state): State<AppState>,
    id: Identity,
    ApiJson(body): ApiJson<SettingsBody>,
) -> Result<Json<SettingsBody>, ApiError> {
    let timezone = body.timezone.trim().to_string();
    if !clock::is_valid_zone(&timezone) {
        return Err(bad(
            "unknown time zone; use an IANA name like Europe/Stockholm",
        ));
    }
    let hhmm = |s: &str| parse_hhmm(s.trim()).ok_or_else(|| bad("slot times must be HH:MM"));
    let times = SlotTimes {
        morning: hhmm(&body.slot_times.morning)?,
        before_lunch: hhmm(&body.slot_times.before_lunch)?,
        afternoon: hhmm(&body.slot_times.afternoon)?,
        evening: hhmm(&body.slot_times.evening)?,
    };
    if !times.is_ordered() {
        return Err(bad(
            "slot times must increase: morning < before_lunch < afternoon < evening",
        ));
    }
    let user_id = id.user.id;
    let user = state
        .db(move |c| db::update_settings(c, user_id, &timezone, times))
        .await?;
    Ok(Json(settings_view(&user)))
}

// ---- tasks ------------------------------------------------------------------

async fn list_tasks(
    State(state): State<AppState>,
    id: Identity,
) -> Result<Json<Vec<TaskView>>, ApiError> {
    let ctx = Ctx::new(&state, id.user);
    let user_id = ctx.user.id;
    let day = ctx.local.day_key.clone();
    let tasks = state.db(move |c| db::open_tasks(c, user_id, &day)).await?;
    let mut views: Vec<TaskView> = tasks.into_iter().map(|t| view(t, &ctx)).collect();
    sort_views(&mut views);
    Ok(Json(views))
}

fn sort_views(views: &mut [TaskView]) {
    views.sort_by(|a, b| {
        b.urgency
            .partial_cmp(&a.urgency)
            .unwrap_or(std::cmp::Ordering::Equal)
            .then(a.task.id.cmp(&b.task.id))
    });
}

#[derive(Deserialize)]
struct NewTask {
    title: String,
    #[serde(default = "default_kind")]
    kind: db::Kind,
    due_on: Option<String>,
    after_days: Option<i64>,
    ramp_days: Option<i64>,
    slots: Option<Vec<Slot>>,
}

fn default_kind() -> db::Kind {
    db::Kind::Once
}

/// Checks the fields that belong to each kind. Returns the cleaned due date
/// and slots (deduplicated, in day order).
fn validate_new_task(body: &NewTask) -> Result<(Option<String>, Option<Vec<Slot>>), ApiError> {
    let due_on = body.due_on.clone().filter(|d| !d.is_empty());
    let has_interval_fields = body.after_days.is_some() || body.ramp_days.is_some();
    match body.kind {
        db::Kind::Once => {
            if has_interval_fields {
                return Err(bad("after_days and ramp_days are for interval tasks"));
            }
            if body.slots.is_some() {
                return Err(bad("slots are for slot tasks"));
            }
            if let Some(d) = &due_on
                && date::parse_day(d).is_none()
            {
                return Err(bad("due_on must be YYYY-MM-DD"));
            }
            Ok((due_on, None))
        }
        db::Kind::Interval => {
            if due_on.is_some() {
                return Err(bad("due_on is for one-off tasks"));
            }
            if body.slots.is_some() {
                return Err(bad("slots are for slot tasks"));
            }
            match body.after_days {
                Some(a) if (0..=MAX_DAYS).contains(&a) => {}
                _ => return Err(bad("after_days must be 0 to 3650")),
            }
            if let Some(r) = body.ramp_days
                && !(1..=MAX_DAYS).contains(&r)
            {
                return Err(bad("ramp_days must be 1 to 3650"));
            }
            Ok((None, None))
        }
        db::Kind::Slot => {
            if due_on.is_some() || has_interval_fields {
                return Err(bad("a slot task takes only title and slots"));
            }
            let mut slots: Vec<Slot> = body.slots.clone().unwrap_or_default();
            slots.sort();
            slots.dedup();
            if slots.is_empty() {
                return Err(bad("slots must name at least one slot"));
            }
            Ok((None, Some(slots)))
        }
    }
}

async fn create_task(
    State(state): State<AppState>,
    id: Identity,
    ApiJson(body): ApiJson<NewTask>,
) -> Result<(StatusCode, Json<TaskView>), ApiError> {
    let title = body.title.trim().to_string();
    if title.is_empty() || title.chars().count() > MAX_TITLE {
        return Err(ApiError::BadRequest(format!(
            "title must be 1 to {MAX_TITLE} characters"
        )));
    }
    let (due_on, slots) = validate_new_task(&body)?;
    let ctx = Ctx::new(&state, id.user);
    let user_id = ctx.user.id;
    let now = ctx.now;
    let day = ctx.local.day_key.clone();
    let task = state
        .db(move |c| {
            let new = db::NewTask {
                title: &title,
                kind: body.kind,
                due_on: due_on.as_deref(),
                after_days: body.after_days,
                ramp_days: body.ramp_days,
                slots,
            };
            db::insert_task(c, user_id, &new, now, &day)
        })
        .await?;
    Ok((StatusCode::CREATED, Json(view(task, &ctx))))
}

/// A partial edit. Absent fields keep their value; `due_on: null` clears the
/// date and `ramp_days: null` returns to the default ramp.
#[derive(Deserialize)]
struct TaskPatch {
    title: Option<String>,
    kind: Option<db::Kind>,
    #[serde(default, deserialize_with = "double_option")]
    due_on: Option<Option<String>>,
    after_days: Option<i64>,
    #[serde(default, deserialize_with = "double_option")]
    ramp_days: Option<Option<i64>>,
    slots: Option<Vec<Slot>>,
}

/// Keeps "absent" (outer None, via default) apart from "null" (Some(None)):
/// the derived impl would fold an explicit null into absent.
fn double_option<'de, T, D>(de: D) -> Result<Option<Option<T>>, D::Error>
where
    T: Deserialize<'de>,
    D: serde::Deserializer<'de>,
{
    Option::<T>::deserialize(de).map(Some)
}

async fn patch_task(
    State(state): State<AppState>,
    id: Identity,
    Path(task_id): Path<i64>,
    ApiJson(patch): ApiJson<TaskPatch>,
) -> Result<Json<TaskView>, ApiError> {
    if patch.kind.is_some() {
        return Err(bad("kind cannot change; make a new task"));
    }
    let ctx = Ctx::new(&state, id.user);
    let user_id = ctx.user.id;
    let day = ctx.local.day_key.clone();
    let current = state
        .db({
            let day = day.clone();
            move |c| db::get_task(c, user_id, task_id, &day)
        })
        .await?
        .ok_or(ApiError::NotFound)?;

    let title = match patch.title {
        Some(t) => {
            let t = t.trim().to_string();
            if t.is_empty() || t.chars().count() > MAX_TITLE {
                return Err(ApiError::BadRequest(format!(
                    "title must be 1 to {MAX_TITLE} characters"
                )));
            }
            t
        }
        None => current.title.clone(),
    };
    let mut due_on = current.due_on.clone();
    let mut after_days = current.after_days;
    let mut ramp_days = current.ramp_days;
    let mut slots = current.slots.clone();
    match current.kind {
        db::Kind::Once => {
            if patch.after_days.is_some() || patch.ramp_days.is_some() {
                return Err(bad("after_days and ramp_days are for interval tasks"));
            }
            if patch.slots.is_some() {
                return Err(bad("slots are for slot tasks"));
            }
            if let Some(d) = patch.due_on {
                due_on = d.filter(|x| !x.is_empty());
                if let Some(x) = &due_on
                    && date::parse_day(x).is_none()
                {
                    return Err(bad("due_on must be YYYY-MM-DD"));
                }
            }
        }
        db::Kind::Interval => {
            if patch.due_on.is_some() {
                return Err(bad("due_on is for one-off tasks"));
            }
            if patch.slots.is_some() {
                return Err(bad("slots are for slot tasks"));
            }
            if let Some(a) = patch.after_days {
                if !(0..=MAX_DAYS).contains(&a) {
                    return Err(bad("after_days must be 0 to 3650"));
                }
                after_days = Some(a);
            }
            if let Some(r) = patch.ramp_days {
                if let Some(v) = r
                    && !(1..=MAX_DAYS).contains(&v)
                {
                    return Err(bad("ramp_days must be 1 to 3650"));
                }
                ramp_days = r;
            }
        }
        db::Kind::Slot => {
            if patch.due_on.is_some() || patch.after_days.is_some() || patch.ramp_days.is_some() {
                return Err(bad("a slot task takes only title and slots"));
            }
            if let Some(mut s) = patch.slots {
                s.sort();
                s.dedup();
                if s.is_empty() {
                    return Err(bad("slots must name at least one slot"));
                }
                slots = Some(s);
            }
        }
    }
    let task = state
        .db(move |c| {
            let upd = db::TaskUpdate {
                title: &title,
                due_on: due_on.as_deref(),
                after_days,
                ramp_days,
                slots,
            };
            db::update_task(c, user_id, task_id, &upd, &day)
        })
        .await?
        .ok_or(ApiError::NotFound)?;
    Ok(Json(view(task, &ctx)))
}

#[derive(Deserialize, Default)]
struct DoneBody {
    slot: Option<Slot>,
}

/// Marks a task done. Slot tasks take an optional `{ "slot" }`; without it
/// the most pressing unchecked slot is used, and nothing happens when every
/// slot is already checked.
async fn done_task(
    State(state): State<AppState>,
    id: Identity,
    Path(task_id): Path<i64>,
    body: Bytes,
) -> Result<Json<TaskView>, ApiError> {
    let body: DoneBody = if body.is_empty() {
        DoneBody::default()
    } else {
        serde_json::from_slice(&body).map_err(|e| ApiError::BadRequest(e.to_string()))?
    };
    let ctx = Ctx::new(&state, id.user);
    let user_id = ctx.user.id;
    let day = ctx.local.day_key.clone();
    let current = state
        .db({
            let day = day.clone();
            move |c| db::get_task(c, user_id, task_id, &day)
        })
        .await?
        .ok_or(ApiError::NotFound)?;
    let slot = match current.kind {
        db::Kind::Slot => match body.slot {
            Some(s) if !current.slots.as_deref().unwrap_or_default().contains(&s) => {
                return Err(bad("slot is not one of this task's"));
            }
            Some(s) => s,
            None => {
                let statuses = slot_statuses(&current, &ctx.user.slot_times, ctx.local.minute);
                match pending_slot(&statuses) {
                    Some(s) => s,
                    None => return Ok(Json(view(current, &ctx))),
                }
            }
        }
        .into(),
        _ if body.slot.is_some() => return Err(bad("slot is for slot tasks")),
        _ => None,
    };
    let now = ctx.now;
    let task = state
        .db(move |c| db::complete_task(c, user_id, task_id, now, &day, slot))
        .await?
        .ok_or(ApiError::NotFound)?;
    Ok(Json(view(task, &ctx)))
}

async fn delete_task(
    State(state): State<AppState>,
    id: Identity,
    Path(task_id): Path<i64>,
) -> Result<StatusCode, ApiError> {
    let user_id = id.user.id;
    if state
        .db(move |c| db::delete_task(c, user_id, task_id))
        .await?
    {
        Ok(StatusCode::NO_CONTENT)
    } else {
        Err(ApiError::NotFound)
    }
}

// ---- the /public/ read-only URL ---------------------------------------------

#[derive(Serialize)]
struct PublicUrlStatus {
    enabled: bool,
}

async fn public_url_status(Session(user): Session) -> Json<PublicUrlStatus> {
    Json(PublicUrlStatus {
        enabled: user.has_public_url,
    })
}

/// The path is returned exactly once; only its hash is stored.
#[derive(Serialize)]
struct MintedPublicUrl {
    path: String,
}

async fn mint_public_url(
    State(state): State<AppState>,
    Session(user): Session,
) -> Result<(StatusCode, Json<MintedPublicUrl>), ApiError> {
    let secret = token::generate_with_prefix(token::PUBLIC_PREFIX)
        .map_err(|e| ApiError::Internal(e.to_string()))?;
    let hash = token::hash(&secret);
    state
        .db(move |c| db::set_public_secret_hash(c, user.id, Some(&hash)))
        .await?;
    Ok((
        StatusCode::CREATED,
        Json(MintedPublicUrl {
            path: format!("/public/{secret}/today"),
        }),
    ))
}

async fn disable_public_url(
    State(state): State<AppState>,
    Session(user): Session,
) -> Result<StatusCode, ApiError> {
    state
        .db(move |c| db::set_public_secret_hash(c, user.id, None))
        .await?;
    Ok(StatusCode::NO_CONTENT)
}

#[derive(Serialize)]
struct PublicToday {
    date: String,
    username: String,
    tasks: Vec<TaskView>,
}

/// Today's visible list for callers that cannot set headers (TRMNL pull).
/// The opaque secret in the path is the whole credential.
async fn public_today(
    State(state): State<AppState>,
    Path(secret): Path<String>,
) -> Result<Json<PublicToday>, ApiError> {
    let hash = token::hash(&secret);
    let user = state
        .db(move |c| db::user_by_public_secret_hash(c, &hash))
        .await?
        .ok_or(ApiError::NotFound)?;
    let ctx = Ctx::new(&state, user);
    let user_id = ctx.user.id;
    let day = ctx.local.day_key.clone();
    let tasks = state.db(move |c| db::open_tasks(c, user_id, &day)).await?;
    let mut views: Vec<TaskView> = tasks
        .into_iter()
        .map(|t| view(t, &ctx))
        .filter(|v| v.urgency >= 0.0)
        .collect();
    sort_views(&mut views);
    Ok(Json(PublicToday {
        date: ctx.local.day_key.clone(),
        username: ctx.user.username.clone(),
        tasks: views,
    }))
}

// ---- tokens -----------------------------------------------------------------

async fn list_tokens(
    State(state): State<AppState>,
    Session(user): Session,
) -> Result<Json<Vec<db::Token>>, ApiError> {
    Ok(Json(state.db(move |c| db::list_tokens(c, user.id)).await?))
}

#[derive(Deserialize)]
struct NewToken {
    name: String,
}

/// The token itself, returned exactly once.
#[derive(Serialize)]
struct MintedToken {
    #[serde(flatten)]
    token: db::Token,
    secret: String,
}

async fn create_token(
    State(state): State<AppState>,
    Session(user): Session,
    ApiJson(body): ApiJson<NewToken>,
) -> Result<(StatusCode, Json<MintedToken>), ApiError> {
    let name = body.name.trim().to_string();
    if name.is_empty() || name.chars().count() > MAX_TOKEN_NAME {
        return Err(ApiError::BadRequest(format!(
            "name must be 1 to {MAX_TOKEN_NAME} characters"
        )));
    }
    let secret = token::generate().map_err(|e| ApiError::Internal(e.to_string()))?;
    let prefix = token::display_prefix(&secret);
    let hash = token::hash(&secret);
    let now = state.now();
    let token = state
        .db(move |c| db::insert_token(c, user.id, &name, &prefix, &hash, now))
        .await?;
    Ok((StatusCode::CREATED, Json(MintedToken { token, secret })))
}

async fn delete_token(
    State(state): State<AppState>,
    Session(user): Session,
    Path(token_id): Path<i64>,
) -> Result<StatusCode, ApiError> {
    if state
        .db(move |c| db::delete_token(c, user.id, token_id))
        .await?
    {
        Ok(StatusCode::NO_CONTENT)
    } else {
        Err(ApiError::NotFound)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use axum::body::{Body, to_bytes};
    use axum::http::{Method, Request, header};
    use serde_json::{Value, json};
    use tower::ServiceExt;

    /// 2026-08-30T12:00:00Z: in UTC, morning has passed and before-lunch is on.
    const NOON_UTC: i64 = 1_788_091_200;

    fn app() -> Router {
        app_at(NOON_UTC)
    }

    fn app_at(now: i64) -> Router {
        router(AppState::with_clock(
            db::open_memory(),
            Arc::new(move || now),
        ))
    }

    fn request(
        method: Method,
        path: &str,
        headers: &[(&str, &str)],
        body: Option<Value>,
    ) -> Request<Body> {
        let mut req = Request::builder()
            .method(method)
            .uri(path)
            .header(header::CONTENT_TYPE, "application/json");
        for (k, v) in headers {
            req = req.header(*k, *v);
        }
        let body = body.map(|b| Body::from(b.to_string())).unwrap_or_default();
        req.body(body).unwrap()
    }

    async fn call(app: &Router, req: Request<Body>) -> (StatusCode, Value) {
        let res = app.clone().oneshot(req).await.unwrap();
        let status = res.status();
        let bytes = to_bytes(res.into_body(), usize::MAX).await.unwrap();
        let json = if bytes.is_empty() {
            Value::Null
        } else {
            serde_json::from_slice(&bytes).unwrap()
        };
        (status, json)
    }

    const MAX: (&str, &str) = ("X-Realmdo-User", "max");

    async fn create(app: &Router, body: Value) -> (StatusCode, Value) {
        call(app, request(Method::POST, "/api/tasks", &[MAX], Some(body))).await
    }

    async fn done(app: &Router, id: i64, body: Option<Value>) -> (StatusCode, Value) {
        call(
            app,
            request(Method::POST, &format!("/api/tasks/{id}/done"), &[MAX], body),
        )
        .await
    }

    async fn mint(app: &Router, user: (&str, &str), name: &str) -> (i64, String) {
        let (status, body) = call(
            app,
            request(
                Method::POST,
                "/api/tokens",
                &[user],
                Some(json!({ "name": name })),
            ),
        )
        .await;
        assert_eq!(status, StatusCode::CREATED, "{body}");
        let secret = body["secret"].as_str().unwrap().to_string();
        assert!(secret.starts_with("rdo_"));
        (body["id"].as_i64().unwrap(), secret)
    }

    #[tokio::test]
    async fn no_credentials_is_401() {
        let app = app();
        let (status, _) = call(&app, request(Method::GET, "/api/me", &[], None)).await;
        assert_eq!(status, StatusCode::UNAUTHORIZED);
        let (status, _) = call(
            &app,
            request(
                Method::GET,
                "/api/me",
                &[("Authorization", "Bearer rdo_nope")],
                None,
            ),
        )
        .await;
        assert_eq!(status, StatusCode::UNAUTHORIZED);
    }

    #[tokio::test]
    async fn session_header_identifies_the_user() {
        let app = app();
        let (status, body) = call(&app, request(Method::GET, "/api/me", &[MAX], None)).await;
        assert_eq!(status, StatusCode::OK);
        assert_eq!(body, json!({ "username": "max", "via": "session" }));
    }

    #[tokio::test]
    async fn tokens_authenticate_until_revoked() {
        let app = app();
        let (id, secret) = mint(&app, MAX, "phone").await;
        let bearer = format!("Bearer {secret}");
        let auth: (&str, &str) = ("Authorization", &bearer);

        let (status, body) = call(&app, request(Method::GET, "/api/me", &[auth], None)).await;
        assert_eq!(status, StatusCode::OK);
        assert_eq!(body, json!({ "username": "max", "via": "token" }));

        // The list never carries the secret.
        let (status, body) = call(&app, request(Method::GET, "/api/tokens", &[MAX], None)).await;
        assert_eq!(status, StatusCode::OK);
        assert_eq!(body[0]["name"], "phone");
        assert_eq!(body[0]["prefix"], secret[..12]);
        assert!(body[0].get("secret").is_none());

        let (status, _) = call(
            &app,
            request(Method::DELETE, &format!("/api/tokens/{id}"), &[MAX], None),
        )
        .await;
        assert_eq!(status, StatusCode::NO_CONTENT);
        let (status, _) = call(&app, request(Method::GET, "/api/me", &[auth], None)).await;
        assert_eq!(status, StatusCode::UNAUTHORIZED);
    }

    #[tokio::test]
    async fn the_session_header_wins_over_a_bearer() {
        let app = app();
        let (_, eves) = mint(&app, ("X-Realmdo-User", "eve"), "laptop").await;
        let bearer = format!("Bearer {eves}");
        let (status, body) = call(
            &app,
            request(
                Method::GET,
                "/api/me",
                &[MAX, ("Authorization", &bearer)],
                None,
            ),
        )
        .await;
        assert_eq!(status, StatusCode::OK);
        assert_eq!(body["username"], "max");
        assert_eq!(body["via"], "session");
    }

    #[tokio::test]
    async fn a_token_cannot_manage_tokens() {
        let app = app();
        let (id, secret) = mint(&app, MAX, "phone").await;
        let bearer = format!("Bearer {secret}");
        let auth: (&str, &str) = ("Authorization", &bearer);
        for req in [
            request(Method::GET, "/api/tokens", &[auth], None),
            request(
                Method::POST,
                "/api/tokens",
                &[auth],
                Some(json!({ "name": "more" })),
            ),
            request(Method::DELETE, &format!("/api/tokens/{id}"), &[auth], None),
        ] {
            let (status, _) = call(&app, req).await;
            assert_eq!(status, StatusCode::FORBIDDEN);
        }
        // But it can do real work.
        let (status, _) = call(
            &app,
            request(
                Method::POST,
                "/api/tasks",
                &[auth],
                Some(json!({ "title": "from phone" })),
            ),
        )
        .await;
        assert_eq!(status, StatusCode::CREATED);
    }

    #[tokio::test]
    async fn task_kinds_are_validated() {
        let app = app();
        let (status, body) = create(&app, json!({ "title": "undated" })).await;
        assert_eq!(status, StatusCode::CREATED);
        assert_eq!(body["kind"], "once");
        assert_eq!(body["urgency"], 0.5);
        assert_eq!(body["today"], Value::Null);

        for bad in [
            json!({ "title": "x", "due_on": "2026-02-30" }),
            json!({ "title": "x", "after_days": 3 }),
            json!({ "title": "x", "slots": ["morning"] }),
            json!({ "title": "x", "kind": "interval" }),
            json!({ "title": "x", "kind": "interval", "after_days": -1 }),
            json!({ "title": "x", "kind": "interval", "after_days": 3, "ramp_days": 0 }),
            json!({ "title": "x", "kind": "interval", "after_days": 3, "due_on": "2026-09-01" }),
            json!({ "title": "x", "kind": "slot" }),
            json!({ "title": "x", "kind": "slot", "slots": [] }),
            json!({ "title": "x", "kind": "slot", "slots": ["lunch"] }),
            json!({ "title": "x", "kind": "slot", "slots": ["morning"], "after_days": 1 }),
            json!({ "title": "x", "kind": "weekly" }),
        ] {
            let (status, _) = create(&app, bad.clone()).await;
            assert_eq!(status, StatusCode::BAD_REQUEST, "{bad}");
        }
    }

    #[tokio::test]
    async fn completing_an_interval_task_hides_it_instead_of_archiving() {
        let app = app();
        let (status, body) = create(
            &app,
            json!({ "title": "bathroom", "kind": "interval", "after_days": 6, "ramp_days": 5 }),
        )
        .await;
        assert_eq!(status, StatusCode::CREATED);
        let id = body["id"].as_i64().unwrap();
        // Created today: the clock starts now, so it is hidden for 6 days.
        assert_eq!(body["urgency"], -1.2);
        assert_eq!(body["last_done_at"], Value::Null);

        let (status, body) = done(&app, id, None).await;
        assert_eq!(status, StatusCode::OK);
        assert_eq!(body["done_at"], Value::Null);
        assert_eq!(body["last_done_at"], NOON_UTC);
        assert_eq!(body["urgency"], -1.2);

        // Still listed (the UI decides what to hide), still open.
        let (status, body) = call(&app, request(Method::GET, "/api/tasks", &[MAX], None)).await;
        assert_eq!(status, StatusCode::OK);
        assert_eq!(body.as_array().unwrap().len(), 1);
        assert_eq!(body[0]["kind"], "interval");
    }

    #[tokio::test]
    async fn slot_tasks_track_today_by_the_users_clock() {
        let app = app();
        let (status, body) = create(
            &app,
            json!({ "title": "teeth", "kind": "slot", "slots": ["evening", "morning", "morning"] }),
        )
        .await;
        assert_eq!(status, StatusCode::CREATED, "{body}");
        let id = body["id"].as_i64().unwrap();
        assert_eq!(
            body["slots"],
            json!(["morning", "evening"]),
            "sorted, deduplicated"
        );
        // Noon UTC, default times: morning (07-11) has passed unchecked.
        assert_eq!(
            body["today"],
            json!([
                { "slot": "morning", "state": "overdue" },
                { "slot": "evening", "state": "upcoming" }
            ])
        );
        assert_eq!(body["pending_slot"], "morning");
        assert_eq!(body["urgency"], 1.5);
        assert_eq!(body["important"], true);

        // A bare done checks the pending slot; evening is still ahead.
        let (status, body) = done(&app, id, None).await;
        assert_eq!(status, StatusCode::OK);
        assert_eq!(body["today"][0]["state"], "done");
        assert_eq!(body["pending_slot"], "evening");
        assert_eq!(body["urgency"], -0.5);
        assert_eq!(body["done_at"], Value::Null);

        // A named slot can be checked ahead of time; then nothing is pending.
        let (status, body) = done(&app, id, Some(json!({ "slot": "evening" }))).await;
        assert_eq!(status, StatusCode::OK);
        assert_eq!(body["pending_slot"], Value::Null);
        assert_eq!(body["urgency"], -1.0);
        let (status, body) = done(&app, id, None).await;
        assert_eq!(status, StatusCode::OK, "all done is a no-op, not an error");
        assert_eq!(body["urgency"], -1.0);

        let (status, _) = done(&app, id, Some(json!({ "slot": "afternoon" }))).await;
        assert_eq!(
            status,
            StatusCode::BAD_REQUEST,
            "not one of the task's slots"
        );
        let (status, body) = create(&app, json!({ "title": "plain" })).await;
        assert_eq!(status, StatusCode::CREATED);
        let plain = body["id"].as_i64().unwrap();
        let (status, _) = done(&app, plain, Some(json!({ "slot": "morning" }))).await;
        assert_eq!(status, StatusCode::BAD_REQUEST, "slot on a one-off");
    }

    #[tokio::test]
    async fn settings_move_the_users_clock() {
        let app = app();
        let (status, body) = call(&app, request(Method::GET, "/api/settings", &[MAX], None)).await;
        assert_eq!(status, StatusCode::OK);
        assert_eq!(body["timezone"], "UTC");
        assert_eq!(body["slot_times"]["morning"], "07:00");

        for bad in [
            json!({ "timezone": "Mars/Olympus", "slot_times": { "morning": "07:00", "before_lunch": "11:00", "afternoon": "14:00", "evening": "19:00" } }),
            json!({ "timezone": "UTC", "slot_times": { "morning": "7:00", "before_lunch": "11:00", "afternoon": "14:00", "evening": "19:00" } }),
            json!({ "timezone": "UTC", "slot_times": { "morning": "12:00", "before_lunch": "11:00", "afternoon": "14:00", "evening": "19:00" } }),
        ] {
            let (status, _) = call(
                &app,
                request(Method::PUT, "/api/settings", &[MAX], Some(bad.clone())),
            )
            .await;
            assert_eq!(status, StatusCode::BAD_REQUEST, "{bad}");
        }

        // Noon UTC is 14:00 in Stockholm (CEST): afternoon is current there.
        let sto = json!({ "timezone": "Europe/Stockholm", "slot_times": { "morning": "06:30", "before_lunch": "11:00", "afternoon": "14:00", "evening": "19:00" } });
        let (status, body) = call(
            &app,
            request(Method::PUT, "/api/settings", &[MAX], Some(sto.clone())),
        )
        .await;
        assert_eq!(status, StatusCode::OK);
        assert_eq!(body, sto);
        let (status, body) = create(
            &app,
            json!({ "title": "stretch", "kind": "slot", "slots": ["afternoon"] }),
        )
        .await;
        assert_eq!(status, StatusCode::CREATED);
        assert_eq!(body["today"][0]["state"], "current");
        assert_eq!(body["urgency"], 1.0);
    }

    #[tokio::test]
    async fn tasks_can_be_edited_within_their_kind() {
        let app = app();
        let (_, once) = create(&app, json!({ "title": "book", "due_on": "2026-09-10" })).await;
        let once_id = once["id"].as_i64().unwrap();
        let patch = |id: i64, body: Value| {
            request(
                Method::PATCH,
                &format!("/api/tasks/{id}"),
                &[MAX],
                Some(body),
            )
        };

        let (status, body) = call(
            &app,
            patch(once_id, json!({ "title": "book dentist", "due_on": null })),
        )
        .await;
        assert_eq!(status, StatusCode::OK, "{body}");
        assert_eq!(body["title"], "book dentist");
        assert_eq!(body["due_on"], Value::Null);
        assert_eq!(body["urgency"], 0.5, "undated again");

        for bad in [
            json!({ "kind": "interval" }),
            json!({ "after_days": 3 }),
            json!({ "slots": ["morning"] }),
            json!({ "title": "" }),
        ] {
            let (status, _) = call(&app, patch(once_id, bad.clone())).await;
            assert_eq!(status, StatusCode::BAD_REQUEST, "{bad}");
        }

        let (_, iv) = create(
            &app,
            json!({ "title": "bathroom", "kind": "interval", "after_days": 6, "ramp_days": 5 }),
        )
        .await;
        let iv_id = iv["id"].as_i64().unwrap();
        let (status, body) = call(
            &app,
            patch(iv_id, json!({ "after_days": 3, "ramp_days": null })),
        )
        .await;
        assert_eq!(status, StatusCode::OK);
        assert_eq!(body["after_days"], 3);
        assert_eq!(body["ramp_days"], Value::Null, "back to the default ramp");

        let (_, st) = create(
            &app,
            json!({ "title": "teeth", "kind": "slot", "slots": ["morning"] }),
        )
        .await;
        let st_id = st["id"].as_i64().unwrap();
        let (status, body) = call(
            &app,
            patch(st_id, json!({ "slots": ["evening", "morning"] })),
        )
        .await;
        assert_eq!(status, StatusCode::OK);
        assert_eq!(body["slots"], json!(["morning", "evening"]));

        // Another user's task is invisible to edits.
        let (status, _) = call(
            &app,
            request(
                Method::PATCH,
                &format!("/api/tasks/{once_id}"),
                &[("X-Realmdo-User", "eve")],
                Some(json!({ "title": "mine now" })),
            ),
        )
        .await;
        assert_eq!(status, StatusCode::NOT_FOUND);
    }

    #[tokio::test]
    async fn the_public_url_reads_one_users_visible_list_only() {
        let app = app();
        create(&app, json!({ "title": "visible" })).await;
        create(&app, json!({ "title": "hidden", "due_on": "2026-12-24" })).await;
        call(
            &app,
            request(
                Method::POST,
                "/api/tasks",
                &[("X-Realmdo-User", "eve")],
                Some(json!({ "title": "eves" })),
            ),
        )
        .await;

        let (status, _) = call(
            &app,
            request(Method::GET, "/public/pub_nope/today", &[], None),
        )
        .await;
        assert_eq!(status, StatusCode::NOT_FOUND);

        // Minting needs a session, like token management.
        let (_, secret) = mint(&app, MAX, "phone").await;
        let bearer = format!("Bearer {secret}");
        let (status, _) = call(
            &app,
            request(
                Method::POST,
                "/api/public-url",
                &[("Authorization", &bearer)],
                None,
            ),
        )
        .await;
        assert_eq!(status, StatusCode::FORBIDDEN);

        let (status, body) =
            call(&app, request(Method::GET, "/api/public-url", &[MAX], None)).await;
        assert_eq!(status, StatusCode::OK);
        assert_eq!(body["enabled"], false);
        let (status, body) =
            call(&app, request(Method::POST, "/api/public-url", &[MAX], None)).await;
        assert_eq!(status, StatusCode::CREATED);
        let path = body["path"].as_str().unwrap().to_string();
        assert!(
            path.starts_with("/public/pub_") && path.ends_with("/today"),
            "{path}"
        );

        // No headers at all: the secret in the path is the credential.
        let (status, body) = call(&app, request(Method::GET, &path, &[], None)).await;
        assert_eq!(status, StatusCode::OK);
        assert_eq!(body["username"], "max");
        assert_eq!(body["date"], "2026-08-30");
        let titles: Vec<&str> = body["tasks"]
            .as_array()
            .unwrap()
            .iter()
            .map(|t| t["title"].as_str().unwrap())
            .collect();
        assert_eq!(
            titles,
            vec!["visible"],
            "hidden and other users' tasks stay out"
        );

        // Regenerating rotates; disabling closes the door.
        let (_, body) = call(&app, request(Method::POST, "/api/public-url", &[MAX], None)).await;
        let path2 = body["path"].as_str().unwrap().to_string();
        let (status, _) = call(&app, request(Method::GET, &path, &[], None)).await;
        assert_eq!(status, StatusCode::NOT_FOUND, "old secret is dead");
        let (status, _) = call(&app, request(Method::GET, &path2, &[], None)).await;
        assert_eq!(status, StatusCode::OK);
        let (status, _) = call(
            &app,
            request(Method::DELETE, "/api/public-url", &[MAX], None),
        )
        .await;
        assert_eq!(status, StatusCode::NO_CONTENT);
        let (status, _) = call(&app, request(Method::GET, &path2, &[], None)).await;
        assert_eq!(status, StatusCode::NOT_FOUND);
    }

    #[tokio::test]
    async fn tokens_are_per_user() {
        let app = app();
        let (id, _) = mint(&app, MAX, "phone").await;
        let (status, _) = call(
            &app,
            request(
                Method::DELETE,
                &format!("/api/tokens/{id}"),
                &[("X-Realmdo-User", "eve")],
                None,
            ),
        )
        .await;
        assert_eq!(status, StatusCode::NOT_FOUND);
    }
}
