#!/usr/bin/env bash
# Rebuilds the Android cdylibs (arm64-v8a + x86_64) of the supernotch
# weather map renderer and installs them into the app jniLibs.
# Requires: rustup targets aarch64-linux-android / x86_64-linux-android
# and the Android NDK (path below must exist).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
NDK_BIN="${ANDROID_NDK:-$HOME/.android-sdk/ndk/28.2.13676358}/toolchains/llvm/prebuilt/linux-x86_64/bin"
JNI_LIBS="$ROOT/../app/src/main/jniLibs"
export PATH="$NDK_BIN:$PATH"

build() {
    local target="$1" cc="$2"
    local target_env="${target^^}"
    target_env="${target_env//-/_}"
    env \
        "CARGO_TARGET_${target_env}_LINKER=$NDK_BIN/$cc-clang" \
        "CC_${target//-/_}=$NDK_BIN/$cc-clang" \
        "CXX_${target//-/_}=$NDK_BIN/$cc-clang++" \
        "AR_${target//-/_}=$NDK_BIN/llvm-ar" \
        "RANLIB_${target//-/_}=$NDK_BIN/llvm-ranlib" \
        cargo build --release --lib --manifest-path "$ROOT/Cargo.toml" --target "$target"
}

build aarch64-linux-android aarch64-linux-android24
build x86_64-linux-android x86_64-linux-android24

install -Dm644 "$ROOT/target/aarch64-linux-android/release/libsupernotchweather.so" \
    "$JNI_LIBS/arm64-v8a/libsupernotchweather.so"
install -Dm644 "$ROOT/target/x86_64-linux-android/release/libsupernotchweather.so" \
    "$JNI_LIBS/x86_64/libsupernotchweather.so"
ls -la "$JNI_LIBS"/arm64-v8a "$JNI_LIBS"/x86_64
