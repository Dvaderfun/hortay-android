#!/usr/bin/env bash
#
# Cross-compile TDLib for ios-arm64 (device only) and bundle as an XCFramework.
#
# Usage:
#   ./scripts/build-tdlib-apple.sh                 # use pin from scripts/tdlib-version.txt
#   ./scripts/build-tdlib-apple.sh <SHA|REF>       # override
#
# Env knobs:
#   OPENSSL_VERSION       — default 3.4.1 (TDLib needs ≥ 3.x for iOS)
#   IOS_DEPLOYMENT_TARGET — default 14.0
#   KEEP_SOURCES          — 1 to keep build/apple-build/td and build/apple-build/openssl
#
# Output:
#   libtdlib/build/apple/libtdlight.xcframework/
#     ios-arm64/libtdjson.a       (~50 MB static archive, arm64)
#     ios-arm64/Headers/td/telegram/td_json_client.h
#     Info.plist
#   libtdlib/build/apple/td_api.json  (TL schema dump — driven into TdApiNative generator)
#
# What the script does:
#   1. Resolves the TDLib SHA (override > scripts/tdlib-version.txt > upstream master).
#   2. Clones TDLib into libtdlib/build/apple-build/td.
#   3. Builds OpenSSL 3.x static for ios-arm64 (~5 min after first run).
#   4. Builds TDLib's `tdjson_static` + `td_api_json` for ios-arm64 (~15 min).
#   5. Runs `td_api_json` against the schema to dump td_api.json.
#   6. Creates the XCFramework via `xcodebuild -create-xcframework`.
#   7. Verifies the result with lipo + nm.
#
# Mac + Xcode 16+ required. The script will not run on Linux/Windows.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname -- "${BASH_SOURCE[0]}")/.." >/dev/null && pwd -P)"
LIBTDLIB_DIR="$REPO_ROOT/libtdlib"
BUILD_DIR="$LIBTDLIB_DIR/build/apple-build"
OUT_DIR="$LIBTDLIB_DIR/build/apple"
VERSION_FILE="$REPO_ROOT/scripts/tdlib-version.txt"

OPENSSL_VERSION="${OPENSSL_VERSION:-3.4.1}"
IOS_DEPLOYMENT_TARGET="${IOS_DEPLOYMENT_TARGET:-14.0}"
KEEP_SOURCES="${KEEP_SOURCES:-0}"
JOBS="$(sysctl -n hw.ncpu 2>/dev/null || echo 4)"

log()  { printf '\033[1;34m==>\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m==> warn:\033[0m %s\n' "$*" >&2; }
die()  { printf '\033[1;31m==> error:\033[0m %s\n' "$*" >&2; exit 1; }

# ---------------------------------------------------------------------------
# Preflight.

[ "$(uname -s)" = "Darwin" ] || die "Mac required (Xcode toolchain)"
command -v xcodebuild >/dev/null 2>&1 || die "xcodebuild not on PATH — install Xcode"
command -v cmake      >/dev/null 2>&1 || die "cmake not on PATH (brew install cmake)"
command -v gperf      >/dev/null 2>&1 || die "gperf not on PATH (brew install gperf)"
command -v git        >/dev/null 2>&1 || die "git not on PATH"

XCODE_VERSION_RAW="$(xcodebuild -version | head -n1 | sed 's/Xcode //')"
log "Xcode $XCODE_VERSION_RAW"

# Pick the CMake generator based on what's installed. Ninja is preferred (faster
# incremental); fall back to Unix Makefiles. Decide up-front so we don't leave a
# poisoned CMakeCache.txt behind from a failed first attempt.
if command -v ninja >/dev/null 2>&1; then
    CMAKE_GEN="Ninja"
else
    CMAKE_GEN="Unix Makefiles"
fi
log "CMake generator: $CMAKE_GEN"

# Explicitly point CMake at Apple Clang for the host build. The OpenSSL stage
# exports a cross-toolchain CC; if those exports somehow survive (or shells
# inherit them) CMake's compiler detection fails. Defensive unset.
unset CC CXX CFLAGS CXXFLAGS LDFLAGS CROSS_TOP CROSS_SDK
HOST_CC="$(xcrun --find clang)"
HOST_CXX="$(xcrun --find clang++)"

# ---------------------------------------------------------------------------
# Resolve TDLib SHA.

TDLIB_REF="${1:-}"
if [ -z "$TDLIB_REF" ] && [ -f "$VERSION_FILE" ]; then
    # Read TDLIB_SHA without sourcing (the file also has multi-token vars like
    # ABIS=arm64-v8a x86_64, which would break under `source`).
    PINNED_SHA="$(grep -E '^TDLIB_SHA=' "$VERSION_FILE" | head -n1 | cut -d= -f2 | tr -d '"' | tr -d "'")"
    TDLIB_REF="${PINNED_SHA:-master}"
    log "Using pinned TDLib SHA from $VERSION_FILE: $TDLIB_REF"
fi
[ -n "$TDLIB_REF" ] || TDLIB_REF="master"

# ---------------------------------------------------------------------------
# 1. Clone TDLib.

mkdir -p "$BUILD_DIR"
TD_SRC="$BUILD_DIR/td"
if [ -d "$TD_SRC/.git" ]; then
    log "Re-using existing TDLib clone at $TD_SRC"
    git -C "$TD_SRC" fetch --tags --quiet origin || warn "fetch failed — using local refs"
else
    log "Cloning TDLib into $TD_SRC"
    git clone --quiet https://github.com/tdlib/td.git "$TD_SRC"
fi
log "Checking out $TDLIB_REF"
git -C "$TD_SRC" checkout --quiet "$TDLIB_REF"
TDLIB_RESOLVED_SHA="$(git -C "$TD_SRC" rev-parse HEAD)"
log "Resolved TDLib SHA: $TDLIB_RESOLVED_SHA"

# ---------------------------------------------------------------------------
# 2. Build OpenSSL static for ios-arm64.
#
# OpenSSL 3.x ships an `ios64-cross` Configure target. Set CC/AR via xcrun.

OPENSSL_DIR="$BUILD_DIR/openssl-$OPENSSL_VERSION"
OPENSSL_INSTALL="$BUILD_DIR/openssl-install"
OPENSSL_ARCHIVE="$BUILD_DIR/openssl-$OPENSSL_VERSION.tar.gz"

if [ ! -f "$OPENSSL_INSTALL/lib/libssl.a" ] || [ ! -f "$OPENSSL_INSTALL/lib/libcrypto.a" ]; then
    if [ ! -f "$OPENSSL_ARCHIVE" ]; then
        log "Downloading OpenSSL $OPENSSL_VERSION"
        curl -fL --silent --show-error \
            -o "$OPENSSL_ARCHIVE" \
            "https://github.com/openssl/openssl/releases/download/openssl-$OPENSSL_VERSION/openssl-$OPENSSL_VERSION.tar.gz"
    fi

    rm -rf "$OPENSSL_DIR"
    log "Extracting OpenSSL"
    tar -xf "$OPENSSL_ARCHIVE" -C "$BUILD_DIR"

    log "Configuring OpenSSL ($IOS_DEPLOYMENT_TARGET ios-arm64)"
    pushd "$OPENSSL_DIR" >/dev/null

    export CROSS_TOP="$(xcode-select -p)/Platforms/iPhoneOS.platform/Developer"
    export CROSS_SDK="iPhoneOS.sdk"
    export CC="$(xcrun --sdk iphoneos --find clang)"
    export CFLAGS="-arch arm64 -isysroot $(xcrun --sdk iphoneos --show-sdk-path) -mios-version-min=$IOS_DEPLOYMENT_TARGET"

    ./Configure ios64-cross \
        no-shared no-tests no-async no-engine no-dso \
        --prefix="$OPENSSL_INSTALL" >/dev/null

    log "Building OpenSSL (j$JOBS) — this takes ~5 min"
    make -j"$JOBS" >/dev/null
    make install_sw >/dev/null

    unset CROSS_TOP CROSS_SDK CC CFLAGS
    popd >/dev/null
    log "OpenSSL installed to $OPENSSL_INSTALL"
else
    log "Re-using OpenSSL build at $OPENSSL_INSTALL"
fi

# ---------------------------------------------------------------------------
# 3. Host build → prepare_cross_compiling.
#
# TDLib's tdjson_static depends on auto-generated headers (td_api_json.h,
# e2e_api.h, telegram_api.h, secret_api.h, mtproto_api.h) that are produced
# by running host-compiled binaries against the .tl schema. The iOS cross
# build cannot run those binaries (wrong arch), so we run them in a separate
# host build first via the `prepare_cross_compiling` target. The generated
# headers land inside $TD_SRC/td/generate/auto/ and are picked up
# transparently by any subsequent cross-build configured against $TD_SRC.

TD_HOST_BUILD="$BUILD_DIR/td-build-host"
PREPARE_MARKER="$TD_SRC/td/generate/auto/td/telegram/e2e_api.h"

if [ ! -f "$PREPARE_MARKER" ]; then
    log "Configuring TDLib host build (prepare_cross_compiling)"
    rm -rf "$TD_HOST_BUILD"
    mkdir -p "$TD_HOST_BUILD"
    pushd "$TD_HOST_BUILD" >/dev/null
    cmake -G "$CMAKE_GEN" \
        -DCMAKE_BUILD_TYPE=Release \
        -DCMAKE_C_COMPILER="$HOST_CC" \
        -DCMAKE_CXX_COMPILER="$HOST_CXX" \
        -DTD_ENABLE_LTO=OFF \
        "$TD_SRC"
    log "Building host generators (prepare_cross_compiling)"
    cmake --build . --target prepare_cross_compiling -j"$JOBS"
    popd >/dev/null
    [ -f "$PREPARE_MARKER" ] || die "prepare_cross_compiling did not produce $PREPARE_MARKER"
else
    log "Re-using prepared cross-compile headers (auto/ already populated)"
fi

# Stage the TL schema next to the XCFramework so the TdApi generator
# (scripts/tdapi-gen/, deferred) has a stable input snapshot.
mkdir -p "$OUT_DIR"
cp "$TD_SRC/td/generate/scheme/td_api.tl" "$OUT_DIR/td_api.tl"
log "Staged td_api.tl — $(wc -l <"$OUT_DIR/td_api.tl" | tr -d ' ') lines"

# ---------------------------------------------------------------------------
# 4. Cross-compile tdjson_static for ios-arm64.

TD_IOS_BUILD="$BUILD_DIR/td-build-ios-arm64"
mkdir -p "$TD_IOS_BUILD"

if [ ! -f "$TD_IOS_BUILD/libtdjson_static.a" ] && [ ! -f "$TD_IOS_BUILD/libtdjson.a" ]; then
    log "Configuring TDLib ios-arm64 cross-build"
    rm -rf "$TD_IOS_BUILD"
    mkdir -p "$TD_IOS_BUILD"
    pushd "$TD_IOS_BUILD" >/dev/null
    cmake -G "$CMAKE_GEN" \
        -DCMAKE_SYSTEM_NAME=iOS \
        -DCMAKE_OSX_ARCHITECTURES=arm64 \
        -DCMAKE_OSX_SYSROOT=iphoneos \
        -DCMAKE_OSX_DEPLOYMENT_TARGET="$IOS_DEPLOYMENT_TARGET" \
        -DCMAKE_BUILD_TYPE=Release \
        -DOPENSSL_FOUND=ON \
        -DOPENSSL_CRYPTO_LIBRARY="$OPENSSL_INSTALL/lib/libcrypto.a" \
        -DOPENSSL_SSL_LIBRARY="$OPENSSL_INSTALL/lib/libssl.a" \
        -DOPENSSL_INCLUDE_DIR="$OPENSSL_INSTALL/include" \
        -DCMAKE_FIND_ROOT_PATH="$OPENSSL_INSTALL" \
        -DTD_ENABLE_LTO=OFF \
        -DCMAKE_BUILD_WITH_INSTALL_RPATH=ON \
        "$TD_SRC"
    log "Building tdjson_static (j$JOBS) — this takes ~15 min on Apple Silicon"
    cmake --build . --target tdjson_static -j"$JOBS"
    popd >/dev/null
fi

# Locate the thin wrapper archive CMake produced. TDLib's `tdjson_static`
# target is a 5K wrapper that holds only `td_json_client.cpp.o` +
# `td_log.cpp.o` and EXPORTS the C entry points (td_create_client_id, td_send,
# …). The actual TDLib implementation (`td::Client`, `td::ClientJson`,
# tdcore/tdactor/tdnet/tde2e/tdclient internals) lives in sibling archives —
# CMake's static-library output does NOT bundle transitive dependencies by
# default. Filename has changed across TDLib versions; check both.
THIN_WRAPPER=""
for candidate in "$TD_IOS_BUILD/libtdjson_static.a" "$TD_IOS_BUILD/libtdjson.a"; do
    if [ -f "$candidate" ]; then
        THIN_WRAPPER="$candidate"
        break
    fi
done
[ -n "$THIN_WRAPPER" ] || die "libtdjson_static.a not found under $TD_IOS_BUILD"

# Bundle every transitively-required static archive into a single fat
# `libtdjson_static.a` via `libtool -static`. Without this step the cinterop
# link succeeds (it only sees the C entry points) but `xcodebuild` then fails
# at the iosApp link step with "Undefined symbols: td::Client::Client()…" for
# every TDLib C++ class the wrapper references internally.
#
# We collect:
#   - The wrapper itself (kept FIRST so its `td_*` exports stay primary).
#   - All `.a` files produced under $TD_IOS_BUILD (tdcore, tdactor, tdnet,
#     tde2e, tdclient, tdutils, tdsqlite, tddb, tdmtproto, tdapi, tl, etc.).
#   - OpenSSL static archives (libssl.a + libcrypto.a) — TDLib's `tdnet`
#     calls into OpenSSL directly.
log "Bundling transitive static archives into a fat libtdjson_static.a"
BUNDLE_INPUTS=("$THIN_WRAPPER")
while IFS= read -r -d '' lib; do
    # Skip the wrapper itself (already first in BUNDLE_INPUTS).
    if [ "$lib" != "$THIN_WRAPPER" ]; then
        BUNDLE_INPUTS+=("$lib")
    fi
done < <(find "$TD_IOS_BUILD" -name '*.a' -print0)
BUNDLE_INPUTS+=("$OPENSSL_INSTALL/lib/libssl.a" "$OPENSSL_INSTALL/lib/libcrypto.a")
log "  ${#BUNDLE_INPUTS[@]} input archives → fat libtdjson_static.a"

FAT_ARCHIVE="$TD_IOS_BUILD/libtdjson_static.fat.a"
rm -f "$FAT_ARCHIVE"
libtool -static -no_warning_for_no_symbols -o "$FAT_ARCHIVE" "${BUNDLE_INPUTS[@]}"
# Replace the thin wrapper with the fat archive so downstream lipo/nm/
# xcframework steps see the bundled version.
mv "$FAT_ARCHIVE" "$THIN_WRAPPER"

TDJSON_STATIC="$THIN_WRAPPER"
log "Fat archive: $(du -h "$TDJSON_STATIC" | cut -f1) at $TDJSON_STATIC"

# Verify architecture.
ARCH_INFO="$(lipo -info "$TDJSON_STATIC" 2>&1)"
echo "$ARCH_INFO" | grep -q "arm64" || die "expected arm64 in $TDJSON_STATIC, got: $ARCH_INFO"
log "$ARCH_INFO"

# Verify exported symbols. Note: cannot use `nm | grep -q` here — `set -o
# pipefail` propagates `nm`'s SIGPIPE (when grep matches early and closes
# stdin) as a non-zero pipeline exit even though the symbol IS present. The
# thin pre-bundle archive had ~30 symbols, small enough that grep consumed
# everything before nm finished writing; the bundled fat archive has ~200k
# symbols, so grep -q short-circuits and SIGPIPEs nm. Capture nm's output
# into a variable instead.
SYMS="$(nm -gj "$TDJSON_STATIC" 2>/dev/null)"
for sym in td_create_client_id td_send td_receive td_execute; do
    printf '%s\n' "$SYMS" | grep -Fx "_$sym" >/dev/null || \
        die "symbol $sym missing from $TDJSON_STATIC"
done
log "All five td_* exports resolved (td_create_client_id, td_send, td_receive, td_execute)"

# ---------------------------------------------------------------------------
# 5. Bundle into XCFramework.

XCFRAMEWORK_DIR="$OUT_DIR/libtdlight.xcframework"
HEADERS_STAGE="$BUILD_DIR/headers-stage"

log "Staging headers"
rm -rf "$HEADERS_STAGE"
mkdir -p "$HEADERS_STAGE/td/telegram"
cp "$TD_SRC/td/telegram/td_json_client.h" "$HEADERS_STAGE/td/telegram/"
# Also include the C++ td_log header in case future cinterop wants log control.
cp "$TD_SRC/td/telegram/td_log.h" "$HEADERS_STAGE/td/telegram/" 2>/dev/null || true

# `tdjson_export.h` is auto-generated by CMake's GenerateExportHeader macro,
# but only for the SHARED `tdjson` target. For our static `tdjson_static`
# target the macros are no-ops — every symbol has default visibility because
# nothing is being exported across a dynamic-library boundary. Write a stub
# so consumers (cinterop, manual #include) don't error on the include line.
cat >"$HEADERS_STAGE/td/telegram/tdjson_export.h" <<'EOF'
#ifndef TDJSON_EXPORT_H
#define TDJSON_EXPORT_H
/* Static-library stub: export macros are no-ops. The Apple build links
 * libtdjson_static.a directly into the consumer binary, so symbol visibility
 * is handled by the consumer's linker, not by these markers. */
#define TDJSON_EXPORT
#define TDJSON_NO_EXPORT
#define TDJSON_DEPRECATED __attribute__((__deprecated__))
#define TDJSON_DEPRECATED_EXPORT
#define TDJSON_DEPRECATED_NO_EXPORT
#endif
EOF

log "Creating $XCFRAMEWORK_DIR"
rm -rf "$XCFRAMEWORK_DIR"
xcodebuild -create-xcframework \
    -library "$TDJSON_STATIC" \
    -headers "$HEADERS_STAGE" \
    -output "$XCFRAMEWORK_DIR" >/dev/null
log "XCFramework created"

# ---------------------------------------------------------------------------
# 6. Cleanup intermediate build dirs unless KEEP_SOURCES=1.

if [ "$KEEP_SOURCES" != "1" ]; then
    log "Cleaning intermediate build dirs (KEEP_SOURCES=1 to retain)"
    rm -rf "$TD_IOS_BUILD" "$TD_HOST_BUILD"
    # Keep openssl-install (cinterop links against it) but drop the source tree.
    rm -rf "$OPENSSL_DIR"
fi

log "Done."
log ""
log "Outputs:"
log "  $XCFRAMEWORK_DIR"
log "  $OUT_DIR/td_api.tl"
log ""
log "Next: ./gradlew :shared:compileKotlinIosArm64"
