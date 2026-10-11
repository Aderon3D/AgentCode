#!/usr/bin/env bash
# Rebuild agent-core native sources into androidApp jniLibs.
# The .so files are committed to the repo; run this after editing any
# agent-core/src/androidMain/c/*.c so the APK picks up the change.
#
# Requirements: NDK at /opt/ndk/android-ndk-r29 (see local.properties ndk.dir)
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
NDK="${NDK_DIR:-$(grep '^ndk.dir=' "$ROOT/local.properties" | cut -d= -f2)}"
CLANG="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android24-clang"
OUT="$ROOT/androidApp/src/main/jniLibs/arm64-v8a"

[ -x "$CLANG" ] || { echo "ndk clang not found: $CLANG" >&2; exit 1; }

echo "building libelfloader.so"
"$CLANG" -shared -fPIC -O2 -Wall \
  -o "$OUT/libelfloader.so" \
  "$ROOT/agent-core/src/androidMain/c/elfloader.c" -ldl

echo "ok: $OUT/libelfloader.so ($(stat -c%s "$OUT/libelfloader.so") bytes)"

echo "building libdnsshim.so"
# MUST be glibc, not NDK/bionic: this .so is LD_PRELOADed into the opencode
# child (glibc 2.27 aarch64), not loaded by the Android app. Built with the
# container's gcc (same glibc 2.27 family as the shipped glibc dir).
gcc -shared -fPIC -O2 -Wall \
  -o "$OUT/libdnsshim.so" \
  "$ROOT/agent-core/src/androidMain/c/dnsshim.c" -ldl

echo "ok: $OUT/libdnsshim.so ($(stat -c%s "$OUT/libdnsshim.so") bytes)"
