//! The web UI, embedded from ../web/dist at compile time (read from disk in
//! debug builds). Unknown paths fall back to index.html for the SPA router.

use axum::http::{StatusCode, Uri, header};
use axum::response::{IntoResponse, Response};
use rust_embed::{Embed, EmbeddedFile};

#[derive(Embed)]
#[folder = "../web/dist"]
struct Assets;

pub async fn serve(uri: Uri) -> Response {
    let path = uri.path().trim_start_matches('/');
    if path.starts_with("api/") || path.starts_with("public/") {
        return (StatusCode::NOT_FOUND, "not found").into_response();
    }
    if let Some(file) = Assets::get(path) {
        // Vite hashes everything under assets/, so those can be cached forever.
        let cache = if path.starts_with("assets/") {
            "public, max-age=31536000, immutable"
        } else {
            "no-cache"
        };
        return file_response(file, cache);
    }
    match Assets::get("index.html") {
        Some(index) => file_response(index, "no-cache"),
        None => (StatusCode::SERVICE_UNAVAILABLE, "web UI not built").into_response(),
    }
}

fn file_response(file: EmbeddedFile, cache: &'static str) -> Response {
    let mime = file.metadata.mimetype().to_string();
    (
        [
            (header::CONTENT_TYPE, mime),
            (header::CACHE_CONTROL, cache.to_string()),
        ],
        file.data,
    )
        .into_response()
}
