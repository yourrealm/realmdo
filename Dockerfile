# realmdo image: one binary (the Rust JSON API) on distroless.
#
# Build context is the repo root; .dockerignore narrows it to server/.
# Nothing from ../holden enters this image: realm.tsx describes the deployment
# and never runs in the container.
#
# $BUILDPLATFORM + cross-compilation per $TARGETARCH: nothing here executes
# target code, so both arches build natively on one runner instead of one of
# them running under QEMU (the Realm updater's recipe). Unlike the updater,
# rusqlite's bundled sqlite3.c needs a C cross-compiler, hence Debian's
# gcc-*-linux-gnu packages, glibc targets, and a glibc runtime (distroless/cc).

# --- server: cross-compiled binary --------------------------------------------
FROM --platform=$BUILDPLATFORM rust:1.96-bookworm AS server
ARG TARGETARCH
# Pick the target, and a cross gcc only when the target is not the host arch
# (Debian ships no gcc-<host>-linux-gnu package). Cargo's linker and cc-rs's
# compiler are set through /cross.env so the build step below stays cacheable
# on its own.
RUN set -eux; \
    case "$TARGETARCH" in \
      amd64) triple=x86_64-linux-gnu; target=x86_64-unknown-linux-gnu ;; \
      arm64) triple=aarch64-linux-gnu; target=aarch64-unknown-linux-gnu ;; \
      *) echo "TARGETARCH is '$TARGETARCH' - build with buildx/BuildKit"; exit 1 ;; \
    esac; \
    rustup target add "$target"; \
    printf 'export RUST_TARGET=%s\n' "$target" > /cross.env; \
    if [ "$(dpkg --print-architecture)" != "$TARGETARCH" ]; then \
      apt-get update; \
      apt-get install -y --no-install-recommends \
        "gcc-$(printf %s "$triple" | tr _ -)" "libc6-dev-$TARGETARCH-cross"; \
      rm -rf /var/lib/apt/lists/*; \
      upper="$(printf %s "$target" | tr a-z- A-Z_)"; \
      lower="$(printf %s "$target" | tr - _)"; \
      printf 'export CARGO_TARGET_%s_LINKER=%s-gcc\nexport CC_%s=%s-gcc\n' \
        "$upper" "$triple" "$lower" "$triple" >> /cross.env; \
    fi
WORKDIR /app
COPY server/ ./server/
RUN --mount=type=cache,target=/usr/local/cargo/registry \
    --mount=type=cache,target=/app/server/target \
    set -eux; . /cross.env; \
    cd server; \
    cargo build --release --locked --target "$RUST_TARGET"; \
    cp "target/$RUST_TARGET/release/realmdo" /realmdo

# --- runtime: the binary and glibc, nothing else --------------------------------
# No shell: the compose healthcheck is exec-form (`/app/realmdo healthcheck`).
FROM gcr.io/distroless/cc-debian12
COPY --from=server /realmdo /app/realmdo
ENV PORT=3000
EXPOSE 3000
ENTRYPOINT ["/app/realmdo"]
