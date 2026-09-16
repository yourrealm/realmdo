//! realmdo server: HTTP API + embedded web UI, one SQLite file.
//!
//! `realmdo` serves; `realmdo healthcheck` probes a running server and exits
//! 0/1 (the container has no shell or curl, so the binary checks itself).

mod api;
mod clock;
mod date;
mod db;
mod healthcheck;
mod slots;
mod token;
mod urgency;
mod web;

use std::net::SocketAddr;
use std::path::PathBuf;

fn main() {
    let mut args = std::env::args().skip(1);
    match args.next().as_deref() {
        None => serve(),
        Some("healthcheck") => healthcheck::run(port()),
        Some(other) => {
            eprintln!("unknown command: {other}");
            std::process::exit(2);
        }
    }
}

fn port() -> u16 {
    std::env::var("PORT")
        .ok()
        .and_then(|p| p.parse().ok())
        .unwrap_or(3000)
}

fn data_dir() -> PathBuf {
    std::env::var_os("REALMDO_DATA_DIR")
        .map(PathBuf::from)
        .unwrap_or_else(|| PathBuf::from("/data"))
}

#[tokio::main]
async fn serve() {
    let dir = data_dir();
    std::fs::create_dir_all(&dir)
        .unwrap_or_else(|e| panic!("create data dir {}: {e}", dir.display()));
    let db_path = dir.join("realmdo.db");
    let conn =
        db::open(&db_path).unwrap_or_else(|e| panic!("open database {}: {e}", db_path.display()));

    let app = api::router(api::AppState::new(conn)).fallback(web::serve);
    let addr = SocketAddr::from(([0, 0, 0, 0], port()));
    let listener = tokio::net::TcpListener::bind(addr)
        .await
        .unwrap_or_else(|e| panic!("bind {addr}: {e}"));
    eprintln!("realmdo listening on {addr}, data in {}", dir.display());
    axum::serve(listener, app)
        .with_graceful_shutdown(shutdown())
        .await
        .expect("server error");
}

/// Resolves on SIGINT or SIGTERM so `docker stop` ends the process promptly.
async fn shutdown() {
    let ctrl_c = tokio::signal::ctrl_c();
    let mut term = tokio::signal::unix::signal(tokio::signal::unix::SignalKind::terminate())
        .expect("install SIGTERM handler");
    tokio::select! {
        _ = ctrl_c => {}
        _ = term.recv() => {}
    }
}
